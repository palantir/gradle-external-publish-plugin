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

package com.palantir.gradle.externalpublish

import com.google.common.collect.ImmutableList
import com.palantir.gradle.utils.environmentvariables.EnvironmentVariables
import org.gradle.api.Project

import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.stream.Stream
import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import spock.lang.Unroll

class ExternalPublishRootPluginIntegrationSpec extends IntegrationSpec {
    // ***DELINEATOR FOR REVIEW: PUBLISH_PROJECT_TYPES
    private static final List<String> PUBLISH_PROJECT_TYPES = ImmutableList.of(
            'jar', 'dist', 'application-dist', 'gradle-plugin', 'conjure', 'intellij', 'custom')
    // ***DELINEATOR FOR REVIEW: SONATYPE_PROJECT_TYPES
    private static final List<String> SONATYPE_PROJECT_TYPES = PUBLISH_PROJECT_TYPES - 'gradle-plugin'
    // ***DELINEATOR FOR REVIEW: NON_CONFLICTING_PROJECT_TYPES
    private static final List<String> NON_CONFLICTING_PROJECT_TYPES = PUBLISH_PROJECT_TYPES - 'dist'

    // ***DELINEATOR FOR REVIEW: setup
    def setup() {
        // language=gradle
        settingsFile << '''
            rootProject.name = 'root'
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
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
        '''.stripIndent(true)

        runTasks('writeVersionLocks')
    }

    // ***DELINEATOR FOR REVIEW: publishJar
    File publishJar() {
        publishProject('jar')
    }

    // ***DELINEATOR FOR REVIEW: publishDist
    File publishDist() {
        publishProject('dist')
    }

    // ***DELINEATOR FOR REVIEW: publishApplicationDist
    File publishApplicationDist() {
        publishProject('application-dist')
    }

    // ***DELINEATOR FOR REVIEW: publishGradlePlugin
    File publishGradlePlugin() {
        publishProject('gradle-plugin')
    }

    // ***DELINEATOR FOR REVIEW: publishConjure
    File publishConjure() {
        publishProject('conjure')
    }

    // ***DELINEATOR FOR REVIEW: publishIntellij
    File publishIntellij() {
        publishProject('intellij')
    }

    // ***DELINEATOR FOR REVIEW: publishCustom
    File publishCustom() {
        publishProject('custom')
    }

    // ***DELINEATOR FOR REVIEW: publishProject
    File publishProject(String type, String subprojectName = type) {
        def subprojectDir = new File(projectDir, subprojectName)

        if (!subprojectDir.exists()) {
            addSubproject(subprojectName)
        }

        def subprojectBuildGradle = new File(subprojectDir, 'build.gradle')
        // language=gradle
        subprojectBuildGradle << """
            apply plugin: 'com.palantir.external-publish-${type}'
        """.stripIndent()

        writeHelloWorld(subprojectDir)

        if (type == 'dist') {
            // language=gradle
            subprojectBuildGradle << '''
                task distTar(type: Tar) {
                    archiveFileName = 'foo'
                    destinationDirectory = file('build')
                    compression Compression.GZIP
                    into('/') {
                        from '.'
                        include 'build.gradle'
                    }
                }
            '''.stripIndent(true)
        }

        if (type == 'gradle-plugin') {
            // language=gradle
            subprojectBuildGradle << '''
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
            '''.stripIndent(true)

            writeJavaSourceFile('''
                package com.palantir.external;
                
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;
                public final class TestPlugin implements Plugin<Project> {
                    public void apply(Project project) { }
                }
            '''.stripIndent(), subprojectDir)
        }

        if (type == 'conjure') {
            // language=gradle
            buildFile << '''
                configurations.configureEach { conf ->
                    if (['implementation', 'api', 'conjure'].any { conf.name.contains(it)}) {
                        ['com.palantir.conjure:conjure:4.49.0', 'com.palantir.conjure.java:conjure-java:8.28.0'].each {
                            conf.dependencyConstraints.add(project.dependencies.constraints.create(it))
                        }
                    }
                }
            '''.stripIndent(true)

            def conjureObjectsDir = directory('conjure-objects', subprojectDir)
            settingsFile << "include '${subprojectDir.getName()}:${conjureObjectsDir.getName()}'\n"

            file('src/main/conjure/api.yml', subprojectDir) << '{}'
        }

        if (type == 'intellij') {
            // language=gradle
            subprojectBuildGradle << '''
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
            '''.stripIndent(true)
        }

        if (type == 'custom') {
            // language=gradle
            subprojectBuildGradle << '''
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
            '''.stripIndent(true)
        }

        return subprojectDir
    }

    // ***DELINEATOR FOR REVIEW: allPublishProjects
    void allPublishProjects() {
        PUBLISH_PROJECT_TYPES.each {publishProject(it) }
    }

    // ***DELINEATOR FOR REVIEW: can_apply_plugin_without_signing_without_exploding
    def 'can apply plugin without signing without exploding'() {
        setup:
        allPublishProjects()

        // ***DELINEATOR FOR REVIEW: when
        when:
        ExecutionResult result = runTasksSuccessfully('tasks', '--all', '-i')
        println result.standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        result.success
    }

    // ***DELINEATOR FOR REVIEW: can_publish_jar_to_local_maven_repo_on_disk
    def 'can publish jar to local maven repo on disk'() {
        setup:
        publishJar()
        def mavenRepoDir = testingMavenRepo()

        when:
        runSuccessfullyWithSigning('publishMavenPublicationToTestRepoRepository')

        then:
        def gnv = new File(mavenRepoDir, 'group/jar/version')
        def jarFile = new File(gnv, 'jar-version.jar')

        jarFile.exists()
        new File(gnv, 'jar-version.jar.asc').exists()
        new File(gnv, 'jar-version-javadoc.jar').exists()
        new File(gnv, 'jar-version-javadoc.jar.asc').exists()
        new File(gnv, 'jar-version-sources.jar').exists()
        new File(gnv, 'jar-version-sources.jar.asc').exists()

        verifyPomFile(gnv, 'jar')
        getJarVersionFromManifest(jarFile) == 'version'
    }

    // ***DELINEATOR FOR REVIEW: can_publish_jar_to_local_maven_repo_on_disk_with_version_declared_after_plugin
    def 'can publish jar to local maven repo on disk with version declared after plugin'() {
        setup:
        publishJar()
        new File(projectDir, 'jar/build.gradle') << """
        version = 'updated'
        """.stripIndent()
        def mavenRepoDir = testingMavenRepo()

        // ***DELINEATOR FOR REVIEW: when
        when:
        runSuccessfullyWithSigning('publishMavenPublicationToTestRepoRepository')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def gnv = new File(mavenRepoDir, 'group/jar/updated')
        def jarFile = new File(gnv, 'jar-updated.jar')

        jarFile.exists()
        new File(gnv, 'jar-updated.jar.asc').exists()
        new File(gnv, 'jar-updated-javadoc.jar').exists()
        new File(gnv, 'jar-updated-javadoc.jar.asc').exists()
        new File(gnv, 'jar-updated-sources.jar').exists()
        new File(gnv, 'jar-updated-sources.jar.asc').exists()

        verifyPomFile(gnv, 'jar', 'updated')
        getJarVersionFromManifest(jarFile) == 'updated'
    }

    // ***DELINEATOR FOR REVIEW: getJarVersionFromManifest
    private static String getJarVersionFromManifest(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.manifest.mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
        }
    }

    // ***DELINEATOR FOR REVIEW: can_publish_dist_to_local_maven_repo_on_disk
    def 'can publish dist to local maven repo on disk'() {
        setup:
        publishDist()
        def mavenRepoDir = testingMavenRepo()

        // ***DELINEATOR FOR REVIEW: when
        when:
        runSuccessfullyWithSigning('publishDistPublicationToTestRepoRepository')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def gnv = new File(mavenRepoDir, 'group/dist/version')

        def applicationDistTar = new File(gnv, 'dist-version.tgz')
        applicationDistTar.exists()
        new File(gnv, 'dist-version.tgz.asc').exists()

        verifyPomFile(gnv, 'dist')
    }

    // ***DELINEATOR FOR REVIEW: can_publish_application_dist_to_local_maven_repo_on_disk
    def 'can publish application dist to local maven repo on disk'() {
        setup:
        publishApplicationDist()
        def mavenRepoDir = testingMavenRepo()

        // ***DELINEATOR FOR REVIEW: when
        when:
        runSuccessfullyWithSigning('publishDistPublicationToTestRepoRepository')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def gnv = new File(mavenRepoDir, 'group/application-dist/version')

        def applicationDistTar = new File(gnv, 'application-dist-version.tgz')
        applicationDistTar.exists()
        new File(gnv, 'application-dist-version.tgz.asc').exists()

        // Check that we fix the classpath for windows apps
        def extracted = directory("application-dist-extracted")
        ArchiverFactory.createArchiver(ArchiveFormat.TAR)
                .extract(new GzipCompressorInputStream(new FileInputStream(applicationDistTar)), extracted)
        new File(extracted, "application-dist-version/bin/application-dist.bat").text
                .contains('set CLASSPATH=%APP_HOME%\\lib\\*\r\n')

        verifyPomFile(gnv, 'application-dist')
    }

    // ***DELINEATOR FOR REVIEW: can_publish_conjure_json_to_local_maven_repo_on_disk
    def 'can publish conjure json to local maven repo on disk'() {
        setup:
        publishConjure()
        def mavenRepoDir = testingMavenRepo()

        // ***DELINEATOR FOR REVIEW: when
        when:
        runSuccessfullyWithSigning('publishConjurePublicationToTestRepoRepository')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def gnv = new File(mavenRepoDir, 'group/conjure/version')

        def conjureJson = new File(gnv, 'conjure-version.conjure.json')
        conjureJson.exists()
        new File(gnv, 'conjure-version.conjure.json.asc').exists()

        verifyPomFile(gnv, 'conjure')
    }

    // ***DELINEATOR FOR REVIEW: can_publish_intellij_plugin_to_local_maven_repo_on_disk
    def 'can publish intellij plugin to local maven repo on disk'() {
        setup:
        publishIntellij()
        def mavenRepoDir = testingMavenRepo()

        // ***DELINEATOR FOR REVIEW: when
        when:
        // instrumentCode causes a crash due to some issue with classloaders we don't fully understand
        runTasksSuccessfully('publishIntellijPublicationToTestRepoRepository',
                '-x', ':intellij:instrumentCode',
                '-x', ':intellij:verifyPlugin')

        // ***DELINEATOR FOR REVIEW: then
        then:
        def gnv = new File(mavenRepoDir, 'group/intellij/version')

        new File(gnv, 'intellij-version.zip').exists()
        verifyPomFile(gnv, 'intellij')
    }

    // ***DELINEATOR FOR REVIEW: can_publish_custom_publications_to_local_maven_repo_on_disk
    def 'can publish custom publications to local maven repo on disk'() {
        setup:
        publishCustom()
        def mavenRepoDir = testingMavenRepo()

        // ***DELINEATOR FOR REVIEW: when
        when:
        runSuccessfullyWithSigning(
                'publishFooPublicationToTestRepoRepository',
                'publishBarPublicationToTestRepoRepository',
                '--warning-mode=none')


        // ***DELINEATOR FOR REVIEW: then
        then:
        ['foo', 'bar'].each { artifactId ->
            def gnv = new File(mavenRepoDir, "group/${artifactId}/version")
            verifyPomFile(gnv, artifactId)

            assert new File(gnv, "${artifactId}-version.gradle").exists()
        }
    }

    // ***DELINEATOR FOR REVIEW: verifyPomFile
    void verifyPomFile(File gnv, String artifactId) {
        verifyPomFile(gnv, artifactId, 'version')
    }

    // ***DELINEATOR FOR REVIEW: verifyPomFile
    void verifyPomFile(File gnv, String artifactId, String version) {
        def pom = new XmlParser().parse(new File(gnv, "${artifactId}-${version}.pom"))

        assert pom.groupId.text() == 'group'
        assert pom.artifactId.text() == artifactId
        assert pom.version.text() == version
        // Sonatype requires a description
        assert !pom.description.text().isEmpty()
        assert pom.url.text().endsWith('gradle-external-publish-plugin')

        def license = pom.licenses.license
        assert license.name.text() == 'The Apache License, Version 2.0'
        assert license.url.text() == 'https://www.apache.org/licenses/LICENSE-2.0'

        def developer = pom.developers.developer
        assert developer.id.text() == 'palantir'
        assert developer.name.text() == 'Palantir Technologies Inc'
        assert developer.organizationUrl.text() == 'https://www.palantir.com'

        assert pom.scm.url.text().endsWith('gradle-external-publish-plugin.git')
    }

    // ***DELINEATOR FOR REVIEW: testingMavenRepo
    File testingMavenRepo() {
        def mavenRepoDir = directory('mavenRepo')

        buildFile << """
            subprojects {
                pluginManager.withPlugin('maven-publish') {
                    publishing {
                        repositories {
                            maven {
                                name "testRepo"
                                url "${mavenRepoDir}"
                            }
                        }
                    }
                }
            }
        """.stripIndent()

        return mavenRepoDir
    }

    // ***DELINEATOR FOR REVIEW: signs_jars_correctly
    def 'signs jars correctly'() {
        setup:
        def jarSubprojectDir = publishJar()

        // ***DELINEATOR FOR REVIEW: when
        when:
        runSuccessfullyWithSigning('signMavenPublication')

        // ***DELINEATOR FOR REVIEW: then
        then:
        new File(jarSubprojectDir, 'build/libs/jar-version.jar.asc').exists()
        new File(jarSubprojectDir, 'build/libs/jar-version-javadoc.jar.asc').exists()
        new File(jarSubprojectDir, 'build/libs/jar-version-sources.jar.asc').exists()
    }

    // ***DELINEATOR FOR REVIEW: publish_task_for_type_depends_on_publishing_to_sonatype_on_tag_builds
    @Unroll
    def 'publish task for #type depends on publishing to sonatype on tag builds'() {
        setup:
        publishProject(type)

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning(
                '--dry-run', "-P__TESTING_CIRCLE_TAG=tag", ":${type}:publish").standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.find ":${type}:publish.*PublicationToSonatypeRepository SKIPPED"
        stdout.contains ':closeSonatypeStagingRepository SKIPPED'

        where:
        type << SONATYPE_PROJECT_TYPES
    }

    // ***DELINEATOR FOR REVIEW: fails_with_a_good_error_message_if_signing_is_not_enabled_for_type
    @Unroll
    def 'fails with a good error message if signing is not enabled for #type'() {
        setup:
        publishProject(type)

        // ***DELINEATOR FOR REVIEW: when
        when:
        def errorMessage = runTasksWithFailure(":${type}:publish").failure.cause.cause.message

        // ***DELINEATOR FOR REVIEW: then
        then:
        errorMessage == 'The required environment variables to sign the release could not be found. ' +
                'Check the logs above to find out which ones are missing.'

        where:
        type << SONATYPE_PROJECT_TYPES
    }

    // ***DELINEATOR FOR REVIEW: does_not_check_for_signing_keys_when_on_a_fork
    def 'does not check for signing keys when on a fork'() {
        setup:
        allPublishProjects()

        // ***DELINEATOR FOR REVIEW: when
        when:
        // instrumentCode causes a crash due to some issue with classloaders we don't fully understand
        def executionResult = runTasksSuccessfully(
                'publish',
                '-x', ':intellij:instrumentCode',
                '-x', ':intellij:verifyPlugin',
                '-P__TESTING_CIRCLE_PR_USERNAME=forkyfork')

        // ***DELINEATOR FOR REVIEW: then
        then:
        executionResult.wasSkipped('checkSigningKey')
    }

    // ***DELINEATOR FOR REVIEW: fails_build_if_publish_if_version_ends_in_dirty
    def 'fails build if publish if version ends in dirty'() {
        setup:
        allPublishProjects()

        buildFile << '''
            allprojects {
                version 'version.dirty'
            }
        '''.stripIndent()


        // ***DELINEATOR FOR REVIEW: when
        when:
        def executionResult = runFailingWithSigning('publish')
        println executionResult.standardOutput
        def errorMessage = executionResult.failure.cause.cause.message

        // ***DELINEATOR FOR REVIEW: then
        then:
        errorMessage.contains 'dirty'
    }

    // ***DELINEATOR FOR REVIEW: does_not_init_close_release_or_publish_to_staging_sonatype_repo_if_not_on_a_tag_build
    def 'does not init, close, release or publish to staging sonatype repo if not on a tag build'() {
        // See https://issues.sonatype.org/browse/OSSRH-65523?focusedCommentId=1046249#comment-1046249 for why we can't
        // exercise the publishing codepath on develop - basically it overwhelms Sonatype and harms other users (note
        // there is no per user rate limiting, so it's possible for us to harm everyone else).
        setup:
        allPublishProjects()
        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning('publish').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':initializeSonatypeStagingRepository SKIPPED')
        PUBLISH_PROJECT_TYPES.forEach {type ->
            stdout.find(":${type}:publish.*PublicationToSonatypeRepository SKIPPED")
        }
        !stdout.contains(':closeSonatypeStagingRepository')
        !stdout.contains(':releaseSonatypeStagingRepository')
    }

    // ***DELINEATOR FOR REVIEW: does_release_staging_sonatype_repo_if_on_a_tag_build
    def 'does release staging sonatype repo if on a tag build'() {
        setup:
        allPublishProjects()
        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publish').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':initializeSonatypeStagingRepository UP-TO-DATE')
        PUBLISH_PROJECT_TYPES.forEach {type ->
            stdout.find(":${type}:publish.*PublicationToSonatypeRepository UP-TO-DATE")
        }
        stdout.contains(':closeSonatypeStagingRepository')
        stdout.contains(':releaseSonatypeStagingRepository')
    }

    // ***DELINEATOR FOR REVIEW: does_not_run_publish_tasks_as_a_dependency_of_check_on_normal_run
    def 'does not run publish tasks as a dependency of check on normal run'() {
        setup:
        allPublishProjects()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning(
                '--dry-run', '-P__TESTING_CIRCLE_BRANCH=my-feature-branch', 'check')
                .standardOutput

        println stdout

        // ***DELINEATOR FOR REVIEW: then
        then:
        !stdout.contains(':initializeSonatypeStagingRepository SKIPPED')
        !stdout.contains(':jar:publishMavenPublicationToSonatypeRepository SKIPPED')
        !stdout.contains(':dist:publishDistPublicationToSonatypeRepository SKIPPED')
        !stdout.contains(':closeSonatypeStagingRepository SKIPPED')
        !stdout.contains(':releaseSonatypeStagingRepository SKIPPED')
    }

    // ***DELINEATOR FOR REVIEW: does_not_publish_gradle_plugins_on_publish_on_non_tag_build
    def 'does not publish gradle plugins on publish on non tag build'() {
        setup:
        publishGradlePlugin()
        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning('publish').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':gradle-plugin:publishPlugins SKIPPED')
    }

    // ***DELINEATOR FOR REVIEW: fixes_gradle_26091_when_gradle_plugin_and_jar_are_used_together
    def 'fixes gradle#26091 when gradle plugin and jar are used together' () {
        setup:
        gradleVersion = '8.6'
        def gradlePluginBuildFile = new File(publishGradlePlugin(), 'build.gradle')

        gradlePluginBuildFile<< '''
            apply plugin: 'com.palantir.external-publish-jar'
        '''.stripIndent(true)

        // ***DELINEATOR FOR REVIEW: when
        when:
        ExecutionResult result = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publishToMavenLocal')

        // ***DELINEATOR FOR REVIEW: then
        then:
        result.wasExecuted(":gradle-plugin:publishPluginMavenPublicationToMavenLocal")
        result.wasExecuted(":gradle-plugin:signMavenPublication")
        result.wasExecuted(":gradle-plugin:publishMavenPublicationToMavenLocal")
        !result.standardError.contains("Gradle detected a problem")
    }

    // ***DELINEATOR FOR REVIEW: publishes_gradle_plugins_on_publish_on_tag_build
    def 'publishes gradle plugins on publish on tag build'() {
        setup:
        publishGradlePlugin()
        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publish').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':gradle-plugin:publishPlugins UP-TO-DATE')
    }

    // ***DELINEATOR FOR REVIEW: does_not_publish_gradle_plugin_descriptors_to_sonatype_when_external_publish_jar_is_applied
    def 'does not publish gradle plugin descriptors to sonatype when external-publish-jar is applied'() {
        setup:
        def subprojectDir = publishGradlePlugin()

        file('build.gradle', subprojectDir) << '''
            apply plugin: 'com.palantir.external-publish-jar'
        '''.stripIndent()

        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publish').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':gradle-plugin:publishPluginMavenPublicationToSonatypeRepository SKIPPED')
        stdout.contains(':gradle-plugin:publishTestPluginMarkerMavenPublicationToSonatypeRepository SKIPPED')
        stdout.contains(':gradle-plugin:publishMavenPublicationToSonatypeRepository UP-TO-DATE')
        stdout.contains(':gradle-plugin:publishPlugins UP-TO-DATE')
    }

    // ***DELINEATOR FOR REVIEW: can_publish_all_the_plugins_together_in_one_project_to_sonatype
    def 'can publish all the plugins together in one project to sonatype'() {
        setup:
        NON_CONFLICTING_PROJECT_TYPES.each {type ->
            publishProject(type, 'combined')
        }

        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runSuccessfullyWithSigning('publish').standardOutput
        println stdout

        // ***DELINEATOR FOR REVIEW: then
        then:
        NON_CONFLICTING_PROJECT_TYPES.each {type ->
            stdout.find ":${type}:publish.*PublicationToSonatypeRepository UP-TO-DATE"
        }

    }

    // ***DELINEATOR FOR REVIEW: root_plugin_does_not_need_to_be_explicitly_applied_if_there_is_a_publish_plugin_applied_at_the_root
    def 'root plugin does not need to be explicitly applied if there is a publish plugin applied at the root'() {
        setup:
        publishProject('jar', '.')

        buildFile.text = buildFile.text.replace('''apply plugin: 'com.palantir.external-publish'\n''', '')

        // ***DELINEATOR FOR REVIEW: when
        when:
        def executionResult = runTasksSuccessfully('tasks')

        // ***DELINEATOR FOR REVIEW: then
        then:
        executionResult.success
    }

    // ***DELINEATOR FOR REVIEW: runs_publishToMavenLocal_on_build_when_local_or_on_circle_node_0
    def 'runs publishToMavenLocal on build when local or on circle node 0'() {
        setup:
        publishProject('jar', '.')

        // ***DELINEATOR FOR REVIEW: when
        when: 'on circle node 0 - should run pTML'
        def stdout = runTasksSuccessfully('build', '--dry-run',
                '-P__TESTING_CIRCLE_NODE_INDEX=0').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':publishMavenPublicationToMavenLocal SKIPPED')

        // ***DELINEATOR FOR REVIEW: when
        when: 'on circle node 1 - should not run pTML'
        stdout = runTasksSuccessfully('build', '--dry-run',
                '-P__TESTING_CIRCLE_NODE_INDEX=1').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        !stdout.contains(':publishMavenPublicationToMavenLocal SKIPPED')

        // ***DELINEATOR FOR REVIEW: when
        when: 'locally - should not run pTML'
        stdout = runTasksSuccessfully('build', '--dry-run').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(':publishMavenPublicationToMavenLocal SKIPPED')
    }

    // ***DELINEATOR FOR REVIEW: runs_publish_depends_on_publishPlugin_for_intellij
    def 'runs publish depends on publishPlugin for intellij'() {
        setup:
        publishIntellij()

        // ***DELINEATOR FOR REVIEW: when
        when:
        def stdout = runTasksSuccessfully('publish', '--dry-run').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then:
        stdout.contains(":publishPlugin SKIPPED")
    }

    // ***DELINEATOR FOR REVIEW: publishPlugin_task_runs_only_if_CIRCLE_TAG_is_set
    def 'publishPlugin task runs only if CIRCLE TAG is set'() {
        setup:
        publishIntellij()
        disableAllTaskActions()

        // ***DELINEATOR FOR REVIEW: when
        when: 'on a tag build'

        def stdoutTagBuild = runTasksSuccessfully('publishPlugin', '-P__TESTING_CIRCLE_TAG=tag').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then: 'publishPlugin task should be executed'
        stdoutTagBuild.contains("Skipping task ':intellij:publishPlugin' as it has no actions.")

        // ***DELINEATOR FOR REVIEW: when
        when: 'not on a tag build'
        def stdoutNonTagBuild = runTasksSuccessfully('publishPlugin').standardOutput

        // ***DELINEATOR FOR REVIEW: then
        then: 'publishPlugin task should be skipped'
        stdoutNonTagBuild.contains("Skipping task ':intellij:publishPlugin' as task onlyIf 'Task satisfies onlyIf spec' is false.")
    }

    // ***DELINEATOR FOR REVIEW: Check_versions_lock_is_not_effected_by_intellij_plugin
    def 'Check versions.lock is not effected by intellij plugin' (){
        setup:
        publishIntellij()
        def emptyText = new File("versions.lock").text

        // ***DELINEATOR FOR REVIEW: when
        when:
        runTasksSuccessfully("writeVersionsLock")

        // ***DELINEATOR FOR REVIEW: then
        then:
        def postText = new File("versions.lock").text
        assert emptyText == postText
    }

    // ***DELINEATOR FOR REVIEW: disableAllTaskActions
    private void disableAllTaskActions() {
        //language=groovy
        buildFile << '''
            allprojects {
                afterEvaluate {
                    tasks.configureEach {
                        setActions([])
                    }
                }
            }
        '''.stripIndent()
    }

    // ***DELINEATOR FOR REVIEW: runSuccessfullyWithSigning
    private ExecutionResult runSuccessfullyWithSigning(String... tasks) {
        return runWithSigning({ String... args -> runTasksSuccessfully(args) }, tasks)
    }

    // ***DELINEATOR FOR REVIEW: runFailingWithSigning
    private ExecutionResult runFailingWithSigning(String... tasks) {
        return runWithSigning({ String... args -> runTasksWithFailure(args) }, tasks)
    }

    // ***DELINEATOR FOR REVIEW: runWithSigning
    private ExecutionResult runWithSigning(Closure<ExecutionResult> runTasksMethod, String... tasks) {
        def privateKey = getClass().getClassLoader()
                .getResourceAsStream("testing-gpg-key.pgp")
                .getBytes()

        return runTasksMethod(Stream.concat(Stream.of(
                    '-P__TESTING_GPG_SIGNING_KEY_ID=4F33301C',
                    "-P__TESTING_GPG_SIGNING_KEY=${Base64.getEncoder().encodeToString(privateKey)}",
                    '-P__TESTING_GPG_SIGNING_KEY_PASSWORD=password').map({ it.toString() }),
                Stream.of(tasks)).toArray({new String[it] }))
    }

    // ***DELINEATOR FOR REVIEW: runTasksSuccessfully
    @Override
    ExecutionResult runTasksSuccessfully(String... tasks) {
        def executionResult = runTasks(tasks)
        if (executionResult.failure) {
            println executionResult.standardOutput
            println executionResult.standardError
            executionResult.rethrowFailure()
        }

        return executionResult
    }

    // ***DELINEATOR FOR REVIEW: runTasks
    @Override
    ExecutionResult runTasks(String... tasks) {
        return super.runTasks(Stream.concat(Stream.of("-P__TESTING=true"), Stream.of(tasks)).toArray({ new String[it] }))
    }
}
