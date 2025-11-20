/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.externalpublish;

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.palantir.gradle.testing.GradlePluginTests;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.files.ProjectFile;
import com.palantir.gradle.testing.files.RootProject;
import com.palantir.gradle.testing.files.SubProject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.ArchiverFactory;

@GradlePluginTests
class ExternalPublishRootPluginIntegrationTest {
    // ***DELINEATOR FOR REVIEW: PUBLISH_PROJECT_TYPES
    private static final List<String> PUBLISH_PROJECT_TYPES = ImmutableList.of(
            "jar", "dist", "application-dist", "gradle-plugin", "conjure", "intellij", "custom");
    // ***DELINEATOR FOR REVIEW: SONATYPE_PROJECT_TYPES
    private static final List<String> SONATYPE_PROJECT_TYPES =
            ImmutableList.of("jar", "dist", "application-dist", "conjure", "intellij", "custom");
    // ***DELINEATOR FOR REVIEW: NON_CONFLICTING_PROJECT_TYPES
    private static final List<String> NON_CONFLICTING_PROJECT_TYPES =
            ImmutableList.of("jar", "application-dist", "gradle-plugin", "conjure", "intellij", "custom");

    private RootProject rootProject;
    private GradleInvoker gradle;

    // ***DELINEATOR FOR REVIEW: setup
    @BeforeEach
    void setup(RootProject rootProject, GradleInvoker gradle) {
        this.rootProject = rootProject;
        this.gradle = gradle;

        rootProject.settingsGradle().append("""
                rootProject.name = 'root'
                """);

        standardBuildFile(rootProject.buildGradle());

        gradle.withArgs("writeVersionLocks").buildsSuccessfully();
    }

    private ProjectFile standardBuildFile(ProjectFile buildGradle) {
        return buildGradle.append("""
                buildscript {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }

                    dependencies {
                        classpath 'com.gradle.publish:plugin-publish-plugin:1.3.0'
                        classpath 'com.palantir.gradle.conjure:gradle-conjure:5.51.0'
                        classpath 'com.palantir.gradle.consistentversions:gradle-consistent-versions:2.26.0'
                    }
                }

                apply plugin: 'com.palantir.external-publish'
                apply plugin: 'com.palantir.consistent-versions'

                allprojects {
                    group = 'group'
                    version = 'version'

                    repositories {
                        mavenCentral()
                    }
                }
                """);
    }

    // ***DELINEATOR FOR REVIEW: publishJar
    private SubProject publishJar() {
        return publishProject("jar");
    }

    // ***DELINEATOR FOR REVIEW: publishDist
    private SubProject publishDist() {
        return publishProject("dist");
    }

    // ***DELINEATOR FOR REVIEW: publishApplicationDist
    private SubProject publishApplicationDist() {
        return publishProject("application-dist");
    }

    // ***DELINEATOR FOR REVIEW: publishGradlePlugin
    private SubProject publishGradlePlugin() {
        return publishProject("gradle-plugin");
    }

    // ***DELINEATOR FOR REVIEW: publishConjure
    private SubProject publishConjure() {
        return publishProject("conjure");
    }

    // ***DELINEATOR FOR REVIEW: publishIntellij
    private SubProject publishIntellij() {
        return publishProject("intellij");
    }

    // ***DELINEATOR FOR REVIEW: publishCustom
    private SubProject publishCustom() {
        return publishProject("custom");
    }

    // ***DELINEATOR FOR REVIEW: publishProject
    private SubProject publishProject(String type) {
        return publishProject(type, type);
    }

    private SubProject publishProject(String type, String subprojectName) {
        SubProject subproject = rootProject.subproject(subprojectName);

        subproject.buildGradle().append("""
                apply plugin: 'com.palantir.external-publish-%s'
                """.formatted(type));

        subproject.java().writeClass("""
                package testing;
                public class HelloWorld {
                    public static void main(String[] args) {
                        System.out.println("Hello World!");
                    }
                }
                """);

        if (type.equals("dist")) {
            subproject.buildGradle().append("""
                    task distTar(type: Tar) {
                        archiveFileName = 'foo'
                        destinationDirectory = file('build')
                        compression Compression.GZIP
                        into('/') {
                            from '.'
                            include 'build.gradle'
                        }
                    }
                    """);
        }

        if (type.equals("gradle-plugin")) {
            subproject.buildGradle().append("""
                    gradlePlugin {
                        plugins {
                            test {
                                id = 'com.palantir.testplugin'
                                implementationClass = 'com.palantir.external.TestPlugin'
                                displayName = 'TestPlugin Display'
                                description = 'TestPlugin Description'
                            }
                        }
                    }
                    """);

            subproject.java().writeClass("""
                    package com.palantir.external;

                    import org.gradle.api.Plugin;
                    import org.gradle.api.Project;
                    public final class TestPlugin implements Plugin<Project> {
                        public void apply(Project project) { }
                    }
                    """);
        }

        if (type.equals("conjure")) {
            rootProject.buildGradle().append("""
                    configurations.configureEach { conf ->
                        if (['implementation', 'api', 'conjure'].any { conf.name.contains(it)}) {
                            ['com.palantir.conjure:conjure:4.49.0', 'com.palantir.conjure.java:conjure-java:8.28.0'].each {
                                conf.dependencyConstraints.add(project.dependencies.constraints.create(it))
                            }
                        }
                    }
                    """);

            SubProject conjureObjects = subproject.subproject("conjure-objects");
            subproject.file("src/main/conjure/api.yml").write("{}");
        }

        if (type.equals("intellij")) {
            subproject.buildGradle().append("""
                    intellij{
                        pluginName = 'foo'
                        updateSinceUntilBuild = true
                        version = "2024.1"
                        plugins = ['java', 'org.jetbrains.plugins.gradle']
                    }

                    patchPluginXml {
                        pluginDescription = "bar"
                        sinceBuild = '213'
                        untilBuild = ''
                    }
                    """);
        }

        if (type.equals("custom")) {
            subproject.buildGradle().append("""
                    externalPublishing {
                        publication('foo') {
                            artifactId 'foo'
                            artifact file('build.gradle')
                        }
                        publication('bar') {
                            artifactId 'bar'
                            artifact file('build.gradle')
                        }
                    }
                    """);
        }

        return subproject;
    }

    // ***DELINEATOR FOR REVIEW: allPublishProjects
    private void allPublishProjects() {
        PUBLISH_PROJECT_TYPES.forEach(this::publishProject);
    }

    // ***DELINEATOR FOR REVIEW: can_apply_plugin_without_signing_without_exploding
    @Test
    void can_apply_plugin_without_signing_without_exploding() {
        allPublishProjects();

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult result = gradle.withArgs("tasks", "--all", "-i").buildsSuccessfully();
        System.out.println(result.output());

        // ***DELINEATOR FOR REVIEW: then
        assertThat(result).succeeded();
    }

    // ***DELINEATOR FOR REVIEW: can_publish_jar_to_local_maven_repo_on_disk
    @Test
    void can_publish_jar_to_local_maven_repo_on_disk() {
        publishJar();
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning("publishMavenPublicationToTestRepoRepository");

        // ***DELINEATOR FOR REVIEW: then
        Path gnv = mavenRepoDir.resolve("group/jar/version");
        Path jarFile = gnv.resolve("jar-version.jar");

        assertThat(jarFile).exists();
        assertThat(gnv.resolve("jar-version.jar.asc")).exists();
        assertThat(gnv.resolve("jar-version-javadoc.jar")).exists();
        assertThat(gnv.resolve("jar-version-javadoc.jar.asc")).exists();
        assertThat(gnv.resolve("jar-version-sources.jar")).exists();
        assertThat(gnv.resolve("jar-version-sources.jar.asc")).exists();

        verifyPomFile(gnv, "jar");
        assertThat(getJarVersionFromManifest(jarFile.toFile())).isEqualTo("version");
    }

    // ***DELINEATOR FOR REVIEW: can_publish_jar_to_local_maven_repo_on_disk_with_version_declared_after_plugin
    @Test
    void can_publish_jar_to_local_maven_repo_on_disk_with_version_declared_after_plugin() {
        SubProject jar = publishJar();
        jar.buildGradle().append("""
                version = 'updated'
                """);
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning("publishMavenPublicationToTestRepoRepository");

        // ***DELINEATOR FOR REVIEW: then
        Path gnv = mavenRepoDir.resolve("group/jar/updated");
        Path jarFile = gnv.resolve("jar-updated.jar");

        assertThat(jarFile).exists();
        assertThat(gnv.resolve("jar-updated.jar.asc")).exists();
        assertThat(gnv.resolve("jar-updated-javadoc.jar")).exists();
        assertThat(gnv.resolve("jar-updated-javadoc.jar.asc")).exists();
        assertThat(gnv.resolve("jar-updated-sources.jar")).exists();
        assertThat(gnv.resolve("jar-updated-sources.jar.asc")).exists();

        verifyPomFile(gnv, "jar", "updated");
        assertThat(getJarVersionFromManifest(jarFile.toFile())).isEqualTo("updated");
    }

    // ***DELINEATOR FOR REVIEW: getJarVersionFromManifest
    private static String getJarVersionFromManifest(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ***DELINEATOR FOR REVIEW: can_publish_dist_to_local_maven_repo_on_disk
    @Test
    void can_publish_dist_to_local_maven_repo_on_disk() {
        publishDist();
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning("publishDistPublicationToTestRepoRepository");

        // ***DELINEATOR FOR REVIEW: then
        Path gnv = mavenRepoDir.resolve("group/dist/version");

        Path applicationDistTar = gnv.resolve("dist-version.tgz");
        assertThat(applicationDistTar).exists();
        assertThat(gnv.resolve("dist-version.tgz.asc")).exists();

        verifyPomFile(gnv, "dist");
    }

    // ***DELINEATOR FOR REVIEW: can_publish_application_dist_to_local_maven_repo_on_disk
    @Test
    void can_publish_application_dist_to_local_maven_repo_on_disk() throws Exception {
        publishApplicationDist();
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning("publishDistPublicationToTestRepoRepository");

        // ***DELINEATOR FOR REVIEW: then
        Path gnv = mavenRepoDir.resolve("group/application-dist/version");

        Path applicationDistTar = gnv.resolve("application-dist-version.tgz");
        assertThat(applicationDistTar).exists();
        assertThat(gnv.resolve("application-dist-version.tgz.asc")).exists();

        // Check that we fix the classpath for windows apps
        Path extracted = rootProject.directory("application-dist-extracted");
        ArchiverFactory.createArchiver(ArchiveFormat.TAR)
                .extract(new GzipCompressorInputStream(new FileInputStream(applicationDistTar.toFile())),
                        extracted.toFile());
        String batContent = Files.readString(extracted.resolve("application-dist-version/bin/application-dist.bat"));
        assertThat(batContent).contains("set CLASSPATH=%APP_HOME%\\lib\\*\r\n");

        verifyPomFile(gnv, "application-dist");
    }

    // ***DELINEATOR FOR REVIEW: can_publish_conjure_json_to_local_maven_repo_on_disk
    @Test
    void can_publish_conjure_json_to_local_maven_repo_on_disk() {
        publishConjure();
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning("publishConjurePublicationToTestRepoRepository");

        // ***DELINEATOR FOR REVIEW: then
        Path gnv = mavenRepoDir.resolve("group/conjure/version");

        Path conjureJson = gnv.resolve("conjure-version.conjure.json");
        assertThat(conjureJson).exists();
        assertThat(gnv.resolve("conjure-version.conjure.json.asc")).exists();

        verifyPomFile(gnv, "conjure");
    }

    // ***DELINEATOR FOR REVIEW: can_publish_intellij_plugin_to_local_maven_repo_on_disk
    @Test
    void can_publish_intellij_plugin_to_local_maven_repo_on_disk() {
        publishIntellij();
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        // instrumentCode causes a crash due to some issue with classloaders we don't fully understand
        gradle.withArgs("publishIntellijPublicationToTestRepoRepository",
                        "-x", ":intellij:instrumentCode",
                        "-x", ":intellij:verifyPlugin")
                .buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        Path gnv = mavenRepoDir.resolve("group/intellij/version");

        assertThat(gnv.resolve("intellij-version.zip")).exists();
        verifyPomFile(gnv, "intellij");
    }

    // ***DELINEATOR FOR REVIEW: can_publish_custom_publications_to_local_maven_repo_on_disk
    @Test
    void can_publish_custom_publications_to_local_maven_repo_on_disk() {
        publishCustom();
        Path mavenRepoDir = testingMavenRepo();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning(
                "publishFooPublicationToTestRepoRepository",
                "publishBarPublicationToTestRepoRepository",
                "--warning-mode=none");

        // ***DELINEATOR FOR REVIEW: then
        List.of("foo", "bar").forEach(artifactId -> {
            Path gnv = mavenRepoDir.resolve("group/" + artifactId + "/version");
            verifyPomFile(gnv, artifactId);

            assertThat(gnv.resolve(artifactId + "-version.gradle")).exists();
        });
    }

    // ***DELINEATOR FOR REVIEW: verifyPomFile
    private void verifyPomFile(Path gnv, String artifactId) {
        verifyPomFile(gnv, artifactId, "version");
    }

    // ***DELINEATOR FOR REVIEW: verifyPomFile
    private void verifyPomFile(Path gnv, String artifactId, String version) {
        Path pomFile = gnv.resolve(artifactId + "-" + version + ".pom");
        assertThat(pomFile).exists();

        assertThat(pomFile)
                .hasXPath("/project/groupId", "group")
                .hasXPath("/project/artifactId", artifactId)
                .hasXPath("/project/version", version)
                .hasXPath("/project/url[contains(text(), 'gradle-external-publish-plugin')]")
                .hasXPath("/project/licenses/license/name", "The Apache License, Version 2.0")
                .hasXPath("/project/licenses/license/url", "https://www.apache.org/licenses/LICENSE-2.0")
                .hasXPath("/project/developers/developer/id", "palantir")
                .hasXPath("/project/developers/developer/name", "Palantir Technologies Inc")
                .hasXPath("/project/developers/developer/organizationUrl", "https://www.palantir.com")
                .hasXPath("/project/scm/url[contains(text(), 'gradle-external-publish-plugin.git')]");

        // Sonatype requires a description
        assertThat(pomFile).hasXPath("/project/description[string-length(text()) > 0]");
    }

    // ***DELINEATOR FOR REVIEW: testingMavenRepo
    private Path testingMavenRepo() {
        Path mavenRepoDir = rootProject.directory("mavenRepo");

        rootProject.buildGradle().append("""
                subprojects {
                    pluginManager.withPlugin('maven-publish') {
                        publishing {
                            repositories {
                                maven {
                                    name "testRepo"
                                    url "%s"
                                }
                            }
                        }
                    }
                }
                """.formatted(mavenRepoDir.toUri()));

        return mavenRepoDir;
    }

    // ***DELINEATOR FOR REVIEW: signs_jars_correctly
    @Test
    void signs_jars_correctly() {
        SubProject jarSubproject = publishJar();

        // ***DELINEATOR FOR REVIEW: when
        runSuccessfullyWithSigning("signMavenPublication");

        // ***DELINEATOR FOR REVIEW: then
        assertThat(jarSubproject.path("build/libs/jar-version.jar.asc")).exists();
        assertThat(jarSubproject.path("build/libs/jar-version-javadoc.jar.asc")).exists();
        assertThat(jarSubproject.path("build/libs/jar-version-sources.jar.asc")).exists();
    }

    // ***DELINEATOR FOR REVIEW: publish_task_for_type_depends_on_publishing_to_sonatype_on_tag_builds
    @ParameterizedTest
    @MethodSource("sonatypeProjectTypes")
    void publish_task_for_type_depends_on_publishing_to_sonatype_on_tag_builds(String type) {
        publishProject(type);

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning(
                        "--dry-run", "-P__TESTING_CIRCLE_TAG=tag", ":" + type + ":publish")
                .output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).containsPattern(":" + type + ":publish.*PublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).contains(":closeSonatypeStagingRepository SKIPPED");
    }

    static List<String> sonatypeProjectTypes() {
        return SONATYPE_PROJECT_TYPES;
    }

    // ***DELINEATOR FOR REVIEW: fails_with_a_good_error_message_if_signing_is_not_enabled_for_type
    @ParameterizedTest
    @MethodSource("sonatypeProjectTypes")
    void fails_with_a_good_error_message_if_signing_is_not_enabled_for_type(String type) {
        publishProject(type);

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult result = gradle.withArgs(":" + type + ":publish").buildsWithFailure();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(result).output().contains("The required environment variables to sign the release could not be found. "
                + "Check the logs above to find out which ones are missing.");
    }

    // ***DELINEATOR FOR REVIEW: does_not_check_for_signing_keys_when_on_a_fork
    @Test
    void does_not_check_for_signing_keys_when_on_a_fork() {
        allPublishProjects();

        // ***DELINEATOR FOR REVIEW: when
        // instrumentCode causes a crash due to some issue with classloaders we don't fully understand
        InvocationResult executionResult = gradle.withArgs(
                        "publish",
                        "-x", ":intellij:instrumentCode",
                        "-x", ":intellij:verifyPlugin",
                        "-P__TESTING_CIRCLE_PR_USERNAME=forkyfork")
                .buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(executionResult).task("checkSigningKey").wasSkipped();
    }

    // ***DELINEATOR FOR REVIEW: fails_build_if_publish_if_version_ends_in_dirty
    @Test
    void fails_build_if_publish_if_version_ends_in_dirty() {
        allPublishProjects();

        rootProject.buildGradle().append("""
                allprojects {
                    version 'version.dirty'
                }
                """);

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult executionResult = runFailingWithSigning("publish");
        System.out.println(executionResult.output());

        // ***DELINEATOR FOR REVIEW: then
        assertThat(executionResult).output().contains("dirty");
    }

    // ***DELINEATOR FOR REVIEW: does_not_init_close_release_or_publish_to_staging_sonatype_repo_if_not_on_a_tag_build
    @Test
    void does_not_init_close_release_or_publish_to_staging_sonatype_repo_if_not_on_a_tag_build() {
        // See https://issues.sonatype.org/browse/OSSRH-65523?focusedCommentId=1046249#comment-1046249 for why we can't
        // exercise the publishing codepath on develop - basically it overwhelms Sonatype and harms other users (note
        // there is no per user rate limiting, so it's possible for us to harm everyone else).
        allPublishProjects();
        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning("publish").output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":initializeSonatypeStagingRepository SKIPPED");
        PUBLISH_PROJECT_TYPES.forEach(type -> {
            assertThat(stdout).containsPattern(":" + type + ":publish.*PublicationToSonatypeRepository SKIPPED");
        });
        assertThat(stdout).doesNotContain(":closeSonatypeStagingRepository");
        assertThat(stdout).doesNotContain(":releaseSonatypeStagingRepository");
    }

    // ***DELINEATOR FOR REVIEW: does_release_staging_sonatype_repo_if_on_a_tag_build
    @Test
    void does_release_staging_sonatype_repo_if_on_a_tag_build() {
        allPublishProjects();
        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning("-P__TESTING_CIRCLE_TAG=tag", "publish").output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":initializeSonatypeStagingRepository UP-TO-DATE");
        PUBLISH_PROJECT_TYPES.forEach(type -> {
            assertThat(stdout).containsPattern(":" + type + ":publish.*PublicationToSonatypeRepository UP-TO-DATE");
        });
        assertThat(stdout).contains(":closeSonatypeStagingRepository");
        assertThat(stdout).contains(":releaseSonatypeStagingRepository");
    }

    // ***DELINEATOR FOR REVIEW: does_not_run_publish_tasks_as_a_dependency_of_check_on_normal_run
    @Test
    void does_not_run_publish_tasks_as_a_dependency_of_check_on_normal_run() {
        allPublishProjects();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning(
                        "--dry-run", "-P__TESTING_CIRCLE_BRANCH=my-feature-branch", "check")
                .output();

        System.out.println(stdout);

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).doesNotContain(":initializeSonatypeStagingRepository SKIPPED");
        assertThat(stdout).doesNotContain(":jar:publishMavenPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).doesNotContain(":dist:publishDistPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).doesNotContain(":closeSonatypeStagingRepository SKIPPED");
        assertThat(stdout).doesNotContain(":releaseSonatypeStagingRepository SKIPPED");
    }

    // ***DELINEATOR FOR REVIEW: does_not_publish_gradle_plugins_on_publish_on_non_tag_build
    @Test
    void does_not_publish_gradle_plugins_on_publish_on_non_tag_build() {
        publishGradlePlugin();
        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning("publish").output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":gradle-plugin:publishPlugins SKIPPED");
    }

    // ***DELINEATOR FOR REVIEW: fixes_gradle_26091_when_gradle_plugin_and_jar_are_used_together
    @Test
    void fixes_gradle_26091_when_gradle_plugin_and_jar_are_used_together() {
        SubProject gradlePlugin = publishGradlePlugin();

        gradlePlugin.buildGradle().append("""
                apply plugin: 'com.palantir.external-publish-jar'
                """);

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult result = runSuccessfullyWithSigning("-P__TESTING_CIRCLE_TAG=tag", "publishToMavenLocal");

        // ***DELINEATOR FOR REVIEW: then
        assertThat(result).task(":gradle-plugin:publishPluginMavenPublicationToMavenLocal").wasExecuted();
        assertThat(result).task(":gradle-plugin:signMavenPublication").wasExecuted();
        assertThat(result).task(":gradle-plugin:publishMavenPublicationToMavenLocal").wasExecuted();
        assertThat(result).output().doesNotContain("Gradle detected a problem");
    }

    // ***DELINEATOR FOR REVIEW: publishes_gradle_plugins_on_publish_on_tag_build
    @Test
    void publishes_gradle_plugins_on_publish_on_tag_build() {
        publishGradlePlugin();
        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning("-P__TESTING_CIRCLE_TAG=tag", "publish").output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":gradle-plugin:publishPlugins UP-TO-DATE");
    }

    // ***DELINEATOR FOR REVIEW: does_not_publish_gradle_plugin_descriptors_to_sonatype_when_external_publish_jar_is_applied
    @Test
    void does_not_publish_gradle_plugin_descriptors_to_sonatype_when_external_publish_jar_is_applied() {
        SubProject subproject = publishGradlePlugin();

        subproject.buildGradle().append("""
                apply plugin: 'com.palantir.external-publish-jar'
                """);

        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning("-P__TESTING_CIRCLE_TAG=tag", "publish").output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":gradle-plugin:publishPluginMavenPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).contains(":gradle-plugin:publishTestPluginMarkerMavenPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).contains(":gradle-plugin:publishMavenPublicationToSonatypeRepository UP-TO-DATE");
        assertThat(stdout).contains(":gradle-plugin:publishPlugins UP-TO-DATE");
    }

    // ***DELINEATOR FOR REVIEW: can_publish_all_the_plugins_together_in_one_project_to_sonatype
    @Test
    void can_publish_all_the_plugins_together_in_one_project_to_sonatype() {
        NON_CONFLICTING_PROJECT_TYPES.forEach(type -> publishProject(type, "combined"));

        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = runSuccessfullyWithSigning("publish").output();
        System.out.println(stdout);

        // ***DELINEATOR FOR REVIEW: then
        NON_CONFLICTING_PROJECT_TYPES.forEach(type -> {
            assertThat(stdout).containsPattern(":" + type + ":publish.*PublicationToSonatypeRepository UP-TO-DATE");
        });
    }

    // ***DELINEATOR FOR REVIEW: root_plugin_does_not_need_to_be_explicitly_applied_if_there_is_a_publish_plugin_applied_at_the_root
    @Test
    void root_plugin_does_not_need_to_be_explicitly_applied_if_there_is_a_publish_plugin_applied_at_the_root() {
        publishProject("jar", ".");

        String buildFileContent = rootProject.buildGradle().read();
        rootProject.buildGradle().write(buildFileContent.replace("apply plugin: 'com.palantir.external-publish'\n", ""));

        // ***DELINEATOR FOR REVIEW: when
        InvocationResult executionResult = gradle.withArgs("tasks").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(executionResult).succeeded();
    }

    // ***DELINEATOR FOR REVIEW: runs_publishToMavenLocal_on_build_when_local_or_on_circle_node_0
    @Test
    void runs_publishToMavenLocal_on_build_when_local_or_on_circle_node_0() {
        publishProject("jar", ".");

        // ***DELINEATOR FOR REVIEW: when
        String stdout = gradle.withArgs("build", "--dry-run", "-P__TESTING_CIRCLE_NODE_INDEX=0")
                .buildsSuccessfully()
                .output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":publishMavenPublicationToMavenLocal SKIPPED");

        // ***DELINEATOR FOR REVIEW: when
        stdout = gradle.withArgs("build", "--dry-run", "-P__TESTING_CIRCLE_NODE_INDEX=1")
                .buildsSuccessfully()
                .output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).doesNotContain(":publishMavenPublicationToMavenLocal SKIPPED");

        // ***DELINEATOR FOR REVIEW: when
        stdout = gradle.withArgs("build", "--dry-run").buildsSuccessfully().output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":publishMavenPublicationToMavenLocal SKIPPED");
    }

    // ***DELINEATOR FOR REVIEW: runs_publish_depends_on_publishPlugin_for_intellij
    @Test
    void runs_publish_depends_on_publishPlugin_for_intellij() {
        publishIntellij();

        // ***DELINEATOR FOR REVIEW: when
        String stdout = gradle.withArgs("publish", "--dry-run").buildsSuccessfully().output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdout).contains(":publishPlugin SKIPPED");
    }

    // ***DELINEATOR FOR REVIEW: publishPlugin_task_runs_only_if_CIRCLE_TAG_is_set
    @Test
    void publishPlugin_task_runs_only_if_CIRCLE_TAG_is_set() {
        publishIntellij();
        disableAllTaskActions();

        // ***DELINEATOR FOR REVIEW: when
        String stdoutTagBuild = gradle.withArgs("publishPlugin", "-P__TESTING_CIRCLE_TAG=tag")
                .buildsSuccessfully()
                .output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdoutTagBuild).contains("Skipping task ':intellij:publishPlugin' as it has no actions.");

        // ***DELINEATOR FOR REVIEW: when
        String stdoutNonTagBuild = gradle.withArgs("publishPlugin").buildsSuccessfully().output();

        // ***DELINEATOR FOR REVIEW: then
        assertThat(stdoutNonTagBuild)
                .contains("Skipping task ':intellij:publishPlugin' as task onlyIf 'Task satisfies onlyIf spec' is false.");
    }

    // ***DELINEATOR FOR REVIEW: Check_versions_lock_is_not_effected_by_intellij_plugin
    @Test
    void Check_versions_lock_is_not_effected_by_intellij_plugin() throws IOException {
        publishIntellij();
        String emptyText = Files.readString(rootProject.path("versions.lock"));

        // ***DELINEATOR FOR REVIEW: when
        gradle.withArgs("writeVersionsLock").buildsSuccessfully();

        // ***DELINEATOR FOR REVIEW: then
        String postText = Files.readString(rootProject.path("versions.lock"));
        assertThat(emptyText).isEqualTo(postText);
    }

    // ***DELINEATOR FOR REVIEW: disableAllTaskActions
    private void disableAllTaskActions() {
        rootProject.buildGradle().append("""
                allprojects {
                    afterEvaluate {
                        tasks.configureEach {
                            setActions([])
                        }
                    }
                }
                """);
    }

    // ***DELINEATOR FOR REVIEW: runSuccessfullyWithSigning
    private InvocationResult runSuccessfullyWithSigning(String... tasks) {
        return runWithSigning(true, tasks);
    }

    // ***DELINEATOR FOR REVIEW: runFailingWithSigning
    private InvocationResult runFailingWithSigning(String... tasks) {
        return runWithSigning(false, tasks);
    }

    // ***DELINEATOR FOR REVIEW: runWithSigning
    private InvocationResult runWithSigning(boolean expectSuccess, String... tasks) {
        byte[] privateKey;
        try {
            privateKey = getClass()
                    .getClassLoader()
                    .getResourceAsStream("testing-gpg-key.pgp")
                    .readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        GradleInvoker invoker = gradle.withArgs(
                "-P__TESTING_GPG_SIGNING_KEY_ID=4F33301C",
                "-P__TESTING_GPG_SIGNING_KEY=" + Base64.getEncoder().encodeToString(privateKey),
                "-P__TESTING_GPG_SIGNING_KEY_PASSWORD=password",
                "-P__TESTING=true");

        for (String task : tasks) {
            invoker = invoker.withArgs(task);
        }

        return expectSuccess ? invoker.buildsSuccessfully() : invoker.buildsWithFailure();
    }
}
