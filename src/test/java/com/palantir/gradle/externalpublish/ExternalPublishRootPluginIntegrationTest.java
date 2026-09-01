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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.execution.Options;
import com.palantir.gradle.testing.files.Directory;
import com.palantir.gradle.testing.junit.AdditionallyRunWithGradle;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.GradleProject;
import com.palantir.gradle.testing.project.RootProject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.ArchiverFactory;

@GradlePluginTests
@DisabledConfigurationCache
class ExternalPublishRootPluginIntegrationTest {
    private static final List<String> PUBLISH_PROJECT_TYPES =
            List.of("jar", "dist", "application-dist", "gradle-plugin", "conjure", "intellij", "custom");
    private static final List<String> SONATYPE_PROJECT_TYPES = PUBLISH_PROJECT_TYPES.stream()
            .filter(type -> !type.equals("gradle-plugin"))
            .toList();
    private static final List<String> NON_CONFLICTING_PROJECT_TYPES =
            PUBLISH_PROJECT_TYPES.stream().filter(type -> !type.equals("dist")).toList();

    @BeforeEach
    void beforeEach(RootProject rootProject, GradleInvoker gradle) {
        rootProject
                .buildGradle()
                .plugins()
                .add("com.palantir.external-publish")
                .add("com.palantir.consistent-versions");

        rootProject.buildGradle().append("""
            allprojects {
                group = 'group'
                version = 'version'

                repositories {
                    mavenCentral()
                }
            }
            """);

        gradle.withArgs("writeVersionLocks").buildsSuccessfully();
    }

    private GradleProject publishJar(RootProject rootProject) {
        return publishProject(rootProject, "jar");
    }

    private GradleProject publishDist(RootProject rootProject) {
        return publishProject(rootProject, "dist");
    }

    private GradleProject publishApplicationDist(RootProject rootProject) {
        return publishProject(rootProject, "application-dist");
    }

    private GradleProject publishGradlePlugin(RootProject rootProject) {
        return publishProject(rootProject, "gradle-plugin");
    }

    private GradleProject publishConjure(RootProject rootProject) {
        return publishProject(rootProject, "conjure");
    }

    private GradleProject publishIntellij(RootProject rootProject) {
        return publishProject(rootProject, "intellij");
    }

    private GradleProject publishCustom(RootProject rootProject) {
        return publishProject(rootProject, "custom");
    }

    private GradleProject publishProject(RootProject rootProject, String type) {
        return publishProject(rootProject, type, type);
    }

    private GradleProject publishProject(RootProject rootProject, String type, String subprojectName) {
        GradleProject project;
        if (subprojectName.equals(".")) {
            project = rootProject;
        } else {
            project = rootProject.subproject(subprojectName);
        }

        project.buildGradle().plugins().add("com.palantir.external-publish-%s".formatted(type));

        writeHelloWorld(project);

        if (type.equals("dist")) {
            project.buildGradle().append("""
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
            project.buildGradle().append("""
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

            project.mainSourceSet().java().writeClass("""
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

            Directory conjureObjectsDir = project.directory("conjure-objects").createDirectories();
            rootProject
                    .settingsGradle()
                    .include("%s:%s"
                            .formatted(
                                    project.path().getFileName().toString(),
                                    conjureObjectsDir.path().getFileName().toString()));

            project.file("src/main/conjure/api.yml").overwrite("{}");
        }

        if (type.equals("intellij")) {
            project.buildGradle().append("""
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
            project.buildGradle().append("""
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

        return project;
    }

    private void allPublishProjects(RootProject rootProject) {
        PUBLISH_PROJECT_TYPES.forEach(type -> publishProject(rootProject, type));
    }

    private void writeHelloWorld(GradleProject project) {
        project.mainSourceSet().java().writeClass("""
            public class HelloWorld {
                public String getMessage() {
                    return "Hello World!";
                }
            }
            """);
    }

    @Test
    void can_apply_plugin_without_signing_without_exploding(RootProject rootProject, GradleInvoker gradle) {
        allPublishProjects(rootProject);

        InvocationResult result = gradle.withArgs("tasks", "--all", "-i").buildsSuccessfully();

        assertThat(result.output()).isNotEmpty();
    }

    @Test
    void can_publish_jar_to_local_maven_repo_on_disk(RootProject rootProject, GradleInvoker gradle) {
        publishJar(rootProject);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        runSuccessfullyWithSigning(gradle, "publishMavenPublicationToTestRepoRepository");

        Path gnv = mavenRepoDir.path().resolve("group/jar/version");
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

    @Test
    void can_publish_jar_to_local_maven_repo_on_disk_with_version_declared_after_plugin(
            RootProject rootProject, GradleInvoker gradle) {
        publishJar(rootProject);
        rootProject.directory("jar").file("build.gradle").append("""
            version = 'updated'
            """);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        runSuccessfullyWithSigning(gradle, "publishMavenPublicationToTestRepoRepository");

        Path gnv = mavenRepoDir.path().resolve("group/jar/updated");
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

    private static String getJarVersionFromManifest(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void can_publish_dist_to_local_maven_repo_on_disk(RootProject rootProject, GradleInvoker gradle) {
        publishDist(rootProject);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        runSuccessfullyWithSigning(gradle, "publishDistPublicationToTestRepoRepository");

        Path gnv = mavenRepoDir.path().resolve("group/dist/version");

        Path applicationDistTar = gnv.resolve("dist-version.tgz");
        assertThat(applicationDistTar).exists();
        assertThat(gnv.resolve("dist-version.tgz.asc")).exists();

        verifyPomFile(gnv, "dist");
    }

    @Test
    void can_publish_application_dist_to_local_maven_repo_on_disk(RootProject rootProject, GradleInvoker gradle)
            throws Exception {
        publishApplicationDist(rootProject);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        runSuccessfullyWithSigning(gradle, "publishDistPublicationToTestRepoRepository");

        Path gnv = mavenRepoDir.path().resolve("group/application-dist/version");

        Path applicationDistTar = gnv.resolve("application-dist-version.tgz");
        assertThat(applicationDistTar).exists();
        assertThat(gnv.resolve("application-dist-version.tgz.asc")).exists();

        Directory extracted = rootProject.directory("application-dist-extracted");
        extracted.createDirectories();
        ArchiverFactory.createArchiver(ArchiveFormat.TAR)
                .extract(
                        new GzipCompressorInputStream(new FileInputStream(applicationDistTar.toFile())),
                        extracted.path().toFile());
        assertThat(Files.readString(extracted.path().resolve("application-dist-version/bin/application-dist.bat")))
                .contains("set CLASSPATH=%APP_HOME%\\lib\\*\r\n");

        verifyPomFile(gnv, "application-dist");
    }

    @Test
    void can_publish_conjure_json_to_local_maven_repo_on_disk(RootProject rootProject, GradleInvoker gradle) {
        publishConjure(rootProject);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        runSuccessfullyWithSigning(gradle, "publishConjurePublicationToTestRepoRepository");

        Path gnv = mavenRepoDir.path().resolve("group/conjure/version");

        Path conjureJson = gnv.resolve("conjure-version.conjure.json");
        assertThat(conjureJson).exists();
        assertThat(gnv.resolve("conjure-version.conjure.json.asc")).exists();

        verifyPomFile(gnv, "conjure");
    }

    @Test
    void can_publish_intellij_plugin_to_local_maven_repo_on_disk(RootProject rootProject, GradleInvoker gradle) {
        publishIntellij(rootProject);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        gradle.withArgs(
                        "publishIntellijPublicationToTestRepoRepository",
                        "-x",
                        ":intellij:instrumentCode",
                        "-x",
                        ":intellij:verifyPlugin")
                .buildsSuccessfully();

        Path gnv = mavenRepoDir.path().resolve("group/intellij/version");

        assertThat(gnv.resolve("intellij-version.zip")).exists();
        verifyPomFile(gnv, "intellij");
    }

    @Test
    void can_publish_custom_publications_to_local_maven_repo_on_disk(RootProject rootProject, GradleInvoker gradle) {
        publishCustom(rootProject);
        Directory mavenRepoDir = testingMavenRepo(rootProject);

        runSuccessfullyWithSigning(
                gradle,
                "publishFooPublicationToTestRepoRepository",
                "publishBarPublicationToTestRepoRepository",
                "--warning-mode=none");

        List.of("foo", "bar").forEach(artifactId -> {
            Path gnv = mavenRepoDir.path().resolve("group/%s/version".formatted(artifactId));
            verifyPomFile(gnv, artifactId);

            assertThat(gnv.resolve("%s-version.gradle".formatted(artifactId))).exists();
        });
    }

    private void verifyPomFile(Path gnv, String artifactId) {
        verifyPomFile(gnv, artifactId, "version");
    }

    private void verifyPomFile(Path gnv, String artifactId, String version) {
        try {
            Path pomPath = gnv.resolve("%s-%s.pom".formatted(artifactId, version));
            String pomContent = Files.readString(pomPath);

            PomProject pom = XmlMapper.builder().build().readValue(pomContent, PomProject.class);

            assertThat(pom.groupId()).isEqualTo("group");
            assertThat(pom.artifactId()).isEqualTo(artifactId);
            assertThat(pom.version()).isEqualTo(version);
            assertThat(pom.description())
                    .as("POM description required by Sonatype")
                    .isNotEmpty();
            assertThat(pom.url()).endsWith("gradle-external-publish-plugin");

            assertThat(pom.licenses().license().get(0).name()).isEqualTo("The Apache License, Version 2.0");
            assertThat(pom.licenses().license().get(0).url()).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0");

            assertThat(pom.developers().developer().get(0).id()).isEqualTo("palantir");
            assertThat(pom.developers().developer().get(0).name()).isEqualTo("Palantir Technologies Inc");
            assertThat(pom.developers().developer().get(0).organizationUrl()).isEqualTo("https://www.palantir.com");

            assertThat(pom.scm().url()).contains("gradle-external-publish-plugin");
            assertThat(pom.properties().originBranch()).isNotEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PomProject(
            @JacksonXmlProperty(localName = "groupId") String groupId,
            @JacksonXmlProperty(localName = "artifactId") String artifactId,
            @JacksonXmlProperty(localName = "version") String version,
            @JacksonXmlProperty(localName = "description") String description,
            @JacksonXmlProperty(localName = "url") String url,
            @JacksonXmlProperty(localName = "licenses") PomLicenses licenses,
            @JacksonXmlProperty(localName = "developers") PomDevelopers developers,
            @JacksonXmlProperty(localName = "properties") PomProperties properties,
            @JacksonXmlProperty(localName = "scm") PomScm scm) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record PomLicenses(
                @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "license")
                List<PomLicense> license) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record PomLicense(
                @JacksonXmlProperty(localName = "name") String name,
                @JacksonXmlProperty(localName = "url") String url) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record PomDevelopers(
                @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "developer")
                List<PomDeveloper> developer) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record PomDeveloper(
                @JacksonXmlProperty(localName = "id") String id,
                @JacksonXmlProperty(localName = "name") String name,

                @JacksonXmlProperty(localName = "organizationUrl")
                String organizationUrl) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record PomScm(@JacksonXmlProperty(localName = "url") String url) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record PomProperties(
                @JacksonXmlProperty(localName = "originBranch")
                String originBranch) {}
    }

    private Directory testingMavenRepo(RootProject rootProject) {
        Directory mavenRepoDir = rootProject.directory("mavenRepo").createDirectories();

        rootProject
                .buildGradle()
                .append("""
                    subprojects {
                        pluginManager.withPlugin('maven-publish') {
                            publishing {
                                repositories {
                                    maven {
                                        name "testRepo"
                                        url uri("%s")
                                    }
                                }
                            }
                        }
                    }
                    """, mavenRepoDir.path().toAbsolutePath().toString().replace("\\", "\\\\"));

        return mavenRepoDir;
    }

    @Test
    void signs_jars_correctly(RootProject rootProject, GradleInvoker gradle) {
        Directory jarSubprojectDir = publishJar(rootProject);

        runSuccessfullyWithSigning(gradle, "signMavenPublication");

        assertThat(jarSubprojectDir.path().resolve("build/libs/jar-version.jar.asc"))
                .exists();
        assertThat(jarSubprojectDir.path().resolve("build/libs/jar-version-javadoc.jar.asc"))
                .exists();
        assertThat(jarSubprojectDir.path().resolve("build/libs/jar-version-sources.jar.asc"))
                .exists();
    }

    @ParameterizedTest
    @MethodSource("sonatypeProjectTypes")
    void publish_task_for_type_depends_on_publishing_to_sonatype_on_tag_builds(
            String type, RootProject rootProject, GradleInvoker gradle) {
        publishProject(rootProject, type);

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("--dry-run", ":%s:publish".formatted(type))
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build());
        String stdout = result.output();

        assertThat(stdout).containsPattern(":%s:publish.*PublicationToSonatypeRepository SKIPPED".formatted(type));
        assertThat(stdout).contains(":closeSonatypeStagingRepository SKIPPED");
    }

    static Stream<String> sonatypeProjectTypes() {
        return SONATYPE_PROJECT_TYPES.stream();
    }

    @ParameterizedTest
    @MethodSource("sonatypeProjectTypes")
    void fails_with_a_good_error_message_if_signing_is_not_enabled_for_type(
            String type, RootProject rootProject, GradleInvoker gradle) {
        publishProject(rootProject, type);

        InvocationResult result = gradle.withArgs(":%s:publish".formatted(type)).buildsWithFailure();

        assertThat(result.output())
                .contains("The required environment variables to sign the release could not be found.")
                .contains("Check the logs above to find out which ones are missing.");
    }

    @Test
    void does_not_check_for_signing_keys_when_on_a_fork(RootProject rootProject, GradleInvoker gradle) {
        allPublishProjects(rootProject);

        InvocationResult executionResult = gradle.with(Options.builder()
                        .addArgs("publish", "-x", ":intellij:instrumentCode", "-x", ":intellij:verifyPlugin")
                        .putTestingEnvironmentVariables("CIRCLE_PR_USERNAME", "forkyfork")
                        .build())
                .buildsSuccessfully();

        assertThat(executionResult).task(":checkSigningKey").skipped();
    }

    @Test
    void fails_build_if_publish_if_version_ends_in_dirty(RootProject rootProject, GradleInvoker gradle) {
        allPublishProjects(rootProject);

        rootProject.buildGradle().append("""
            allprojects {
                version 'version.dirty'
            }
            """);

        InvocationResult executionResult = runFailingWithSigning(gradle, "publish");

        assertThat(executionResult.output()).contains("dirty");
    }

    @Test
    void does_not_init_close_release_or_publish_to_staging_sonatype_repo_if_not_on_a_tag_build(
            RootProject rootProject, GradleInvoker gradle) {
        // See https://issues.sonatype.org/browse/OSSRH-65523?focusedCommentId=1046249#comment-1046249 for why we can't
        // exercise the publishing codepath on develop - basically it overwhelms Sonatype and harms other users (note
        // there is no per user rate limiting, so it's possible for us to harm everyone else).
        allPublishProjects(rootProject);
        disableAllTaskActions(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(gradle, "publish");
        String stdout = result.output();

        assertThat(stdout).contains(":initializeSonatypeStagingRepository SKIPPED");
        PUBLISH_PROJECT_TYPES.forEach(type -> {
            assertThat(stdout).containsPattern(":%s:publish.*PublicationToSonatypeRepository SKIPPED".formatted(type));
        });
        assertThat(stdout).doesNotContain(":closeSonatypeStagingRepository");
        assertThat(stdout).doesNotContain(":releaseSonatypeStagingRepository");
    }

    @Test
    void does_release_staging_sonatype_repo_if_on_a_tag_build(RootProject rootProject, GradleInvoker gradle) {
        allPublishProjects(rootProject);
        disableAllTaskActions(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("publish")
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build());
        String stdout = result.output();

        assertThat(stdout).contains(":initializeSonatypeStagingRepository UP-TO-DATE");
        SONATYPE_PROJECT_TYPES.forEach(type -> {
            assertThat(stdout)
                    .containsPattern(":%s:publish.*PublicationToSonatypeRepository UP-TO-DATE".formatted(type));
        });
        assertThat(stdout).contains(":closeSonatypeStagingRepository");
        assertThat(stdout).contains(":releaseSonatypeStagingRepository");
    }

    @Test
    void does_not_run_publish_tasks_as_a_dependency_of_check_on_normal_run(
            RootProject rootProject, GradleInvoker gradle) {
        allPublishProjects(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("--dry-run", "check")
                        .putTestingEnvironmentVariables("CIRCLE_BRANCH", "my-feature-branch")
                        .build());
        String stdout = result.output();

        assertThat(stdout).doesNotContain(":initializeSonatypeStagingRepository SKIPPED");
        assertThat(stdout).doesNotContain(":jar:publishMavenPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).doesNotContain(":dist:publishDistPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).doesNotContain(":closeSonatypeStagingRepository SKIPPED");
        assertThat(stdout).doesNotContain(":releaseSonatypeStagingRepository SKIPPED");
    }

    @Test
    void does_not_publish_gradle_plugins_on_publish_on_non_tag_build(RootProject rootProject, GradleInvoker gradle) {
        publishGradlePlugin(rootProject);
        disableAllTaskActions(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(gradle, "publish");
        String stdout = result.output();

        assertThat(stdout).contains(":gradle-plugin:publishPlugins SKIPPED");
    }

    @Test
    @AdditionallyRunWithGradle("8.6")
    void fixes_gradle_26091_when_gradle_plugin_and_jar_are_used_together(
            RootProject rootProject, GradleInvoker gradle) {
        GradleProject gradlePluginProject = publishGradlePlugin(rootProject);
        gradlePluginProject.buildGradle().plugins().add("com.palantir.external-publish-jar");

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("publishToMavenLocal")
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build());

        assertThat(result)
                .task(":gradle-plugin:publishPluginMavenPublicationToMavenLocal")
                .succeeded();
        assertThat(result).task(":gradle-plugin:signMavenPublication").succeeded();
        assertThat(result)
                .task(":gradle-plugin:publishMavenPublicationToMavenLocal")
                .succeeded();
        assertThat(result.output()).doesNotContain("Gradle detected a problem");
    }

    @Test
    void publishes_gradle_plugins_on_publish_on_tag_build(RootProject rootProject, GradleInvoker gradle) {
        publishGradlePlugin(rootProject);
        disableAllTaskActions(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("publish")
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build());
        String stdout = result.output();

        assertThat(stdout).contains(":gradle-plugin:publishPlugins UP-TO-DATE");
    }

    @Test
    void does_not_publish_gradle_plugin_descriptors_to_sonatype_when_external_publish_jar_is_applied(
            RootProject rootProject, GradleInvoker gradle) {
        GradleProject gradlePluginProject = publishGradlePlugin(rootProject);
        gradlePluginProject.buildGradle().plugins().add("com.palantir.external-publish-jar");

        disableAllTaskActions(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("publish")
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build());
        String stdout = result.output();

        assertThat(stdout).contains(":gradle-plugin:publishPluginMavenPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout)
                .contains(":gradle-plugin:publishTestPluginMarkerMavenPublicationToSonatypeRepository SKIPPED");
        assertThat(stdout).contains(":gradle-plugin:publishMavenPublicationToSonatypeRepository UP-TO-DATE");
        assertThat(stdout).contains(":gradle-plugin:publishPlugins UP-TO-DATE");
    }

    @Test
    void can_publish_all_the_plugins_together_in_one_project_to_sonatype(
            RootProject rootProject, GradleInvoker gradle) {
        NON_CONFLICTING_PROJECT_TYPES.forEach(type -> publishProject(rootProject, type, "combined"));

        disableAllTaskActions(rootProject);

        InvocationResult result = runSuccessfullyWithSigning(
                gradle,
                Options.builder()
                        .addArgs("publish")
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build());

        List.of("Maven", "Dist", "Conjure", "Intellij", "Foo", "Bar")
                .forEach(publication -> assertThat(result)
                        .task(":combined:publish%sPublicationToSonatypeRepository".formatted(publication))
                        .upToDate());
        assertThat(result).task(":combined:publishPlugins").upToDate();
    }

    @Test
    void root_plugin_does_not_need_to_be_explicitly_applied_if_there_is_a_publish_plugin_applied_at_the_root(
            RootProject rootProject, GradleInvoker gradle) {
        publishProject(rootProject, "jar", ".");

        String buildFileText = rootProject.buildGradle().text();
        buildFileText = buildFileText.replace("    id 'com.palantir.external-publish'\n", "");
        rootProject.buildGradle().overwrite(buildFileText);
        assertThat(rootProject.buildGradle().text()).doesNotContain("id 'com.palantir.external-publish'");

        InvocationResult executionResult = gradle.withArgs("tasks").buildsSuccessfully();

        assertThat(executionResult.output()).isNotEmpty();
    }

    @Test
    void runs_publish_to_maven_local_on_build_when_local_or_on_circle_node_0(
            RootProject rootProject, GradleInvoker gradle) {
        publishProject(rootProject, "jar", ".");

        String stdout = gradle.with(Options.builder()
                        .addArgs("build", "--dry-run")
                        .putTestingEnvironmentVariables("CIRCLE_NODE_INDEX", "0")
                        .build())
                .buildsSuccessfully()
                .output();

        assertThat(stdout).contains(":publishMavenPublicationToMavenLocal SKIPPED");

        stdout = gradle.with(Options.builder()
                        .addArgs("build", "--dry-run")
                        .putTestingEnvironmentVariables("CIRCLE_NODE_INDEX", "1")
                        .build())
                .buildsSuccessfully()
                .output();

        assertThat(stdout).doesNotContain(":publishMavenPublicationToMavenLocal SKIPPED");

        stdout = gradle.withArgs("build", "--dry-run").buildsSuccessfully().output();

        assertThat(stdout).contains(":publishMavenPublicationToMavenLocal SKIPPED");
    }

    @Test
    void runs_publish_depends_on_publish_plugin_for_intellij(RootProject rootProject, GradleInvoker gradle) {
        publishIntellij(rootProject);

        String stdout =
                gradle.withArgs("publish", "--dry-run").buildsSuccessfully().output();

        assertThat(stdout).contains(":publishPlugin SKIPPED");
    }

    @Test
    void publish_plugin_task_runs_only_if_circle_tag_is_set(RootProject rootProject, GradleInvoker gradle) {
        publishIntellij(rootProject);
        disableAllTaskActions(rootProject);

        String stdoutTagBuild = gradle.with(Options.builder()
                        .addArgs("publishPlugin", "--info")
                        .putTestingEnvironmentVariables("CIRCLE_TAG", "tag")
                        .build())
                .buildsSuccessfully()
                .output();

        assertThat(stdoutTagBuild).contains("Skipping task ':intellij:publishPlugin' as it has no actions.");

        String stdoutNonTagBuild =
                gradle.withArgs("publishPlugin", "--info").buildsSuccessfully().output();

        assertThat(stdoutNonTagBuild)
                .contains("Skipping task ':intellij:publishPlugin' as task onlyIf 'Task satisfies onlyIf spec' is"
                        + " false.");
    }

    @Test
    void check_versions_lock_is_not_affected_by_intellij_plugin(RootProject rootProject, GradleInvoker gradle)
            throws IOException {
        publishIntellij(rootProject);
        String emptyText = Files.readString(rootProject.path().resolve("versions.lock"));

        gradle.withArgs("writeVersionsLock").buildsSuccessfully();

        String postText = Files.readString(rootProject.path().resolve("versions.lock"));
        assertThat(emptyText).isEqualTo(postText);
    }

    private void disableAllTaskActions(RootProject rootProject) {
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

    private InvocationResult runSuccessfullyWithSigning(GradleInvoker gradle, String... tasks) {
        return runWithSigning(gradle, Options.builder().addArgs(tasks).build(), true);
    }

    private InvocationResult runSuccessfullyWithSigning(GradleInvoker gradle, Options options) {
        return runWithSigning(gradle, options, true);
    }

    private InvocationResult runFailingWithSigning(GradleInvoker gradle, String... tasks) {
        return runWithSigning(gradle, Options.builder().addArgs(tasks).build(), false);
    }

    private InvocationResult runWithSigning(GradleInvoker gradle, Options baseOptions, boolean expectSuccess) {
        byte[] privateKey;
        try {
            privateKey = getClass()
                    .getClassLoader()
                    .getResourceAsStream("testing-gpg-key.pgp")
                    .readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Options options = baseOptions
                .asBuilder()
                .putTestingEnvironmentVariables("GPG_SIGNING_KEY_ID", "4F33301C")
                .putTestingEnvironmentVariables(
                        "GPG_SIGNING_KEY", Base64.getEncoder().encodeToString(privateKey))
                .putTestingEnvironmentVariables("GPG_SIGNING_KEY_PASSWORD", "password")
                .build();

        if (expectSuccess) {
            return gradle.with(options).buildsSuccessfully();
        } else {
            return gradle.with(options).buildsWithFailure();
        }
    }
}
