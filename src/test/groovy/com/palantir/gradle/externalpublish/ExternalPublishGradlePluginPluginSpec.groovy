/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.palantir.gradle.publish.GradlePluginDef
import com.palantir.gradle.publish.PublishGradlePluginUtils
import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult

class ExternalPublishGradlePluginPluginSpec extends IntegrationSpec {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    File buildDir
    File pomFile
    File metaFilesPath

    static final TypeReference<List<GradlePluginDef>> GRADLE_PLUGIN_DEF_TYPE_REF = new TypeReference<List<GradlePluginDef>>() {};

    def setup() {
        buildDir = new File(projectDir, "build")
        pomFile = new File(buildDir, "publications/pluginMaven/pom-default.xml")
        metaFilesPath = new File(projectDir, 'src/main/resources/META-INF/gradle-plugins')

        buildFile << """
            buildscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
                
                dependencies {
                    classpath 'com.gradle.publish:plugin-publish-plugin:1.3.0'
                }
            }

            apply plugin: 'com.palantir.external-publish'
            apply plugin: 'java-gradle-plugin'
            apply plugin: 'com.palantir.external-publish-gradle-plugin'

            group = 'com.palantir.test-palantir'
            version = '0.1.0'

        """.stripIndent(true)

    }

    def 'writes plugin data from gradlePlugin extension'() {
        setup:
        buildFile << '''
            gradlePlugin {
                plugins {
                    testPlugin1 {
                        id = 'com.palantir.test-plugin1'
                        implementationClass = 'com.palantir.gradle.TestPlugin1'
                    }
                    testPlugin2 {
                        id = 'com.palantir.test-plugin2'
                        implementationClass = 'com.palantir.gradle.TestPlugin2'
                    }
                }
            }
        '''
        when:
        ExecutionResult result = runTasksSuccessfully('generatePomFileForPluginMavenPublication')

        then:
        result.wasExecuted('processResources')
        result.wasExecuted('generatePluginMetaData')
        pomFile.exists()

        def xml = new XmlSlurper().parseText(pomFile.text)
        def propString = xml.properties[PublishGradlePluginUtils.PUBLISHED_PLUGIN_IDS_KEY].toString()
        List<GradlePluginDef> publishedPlugins = OBJECT_MAPPER.readValue(propString, GRADLE_PLUGIN_DEF_TYPE_REF)

        publishedPlugins[0].id == 'com.palantir.test-plugin1'
        publishedPlugins[0].implementingClass == 'com.palantir.gradle.TestPlugin1'
        publishedPlugins[1].id == 'com.palantir.test-plugin2'
        publishedPlugins[1].implementingClass == 'com.palantir.gradle.TestPlugin2'
    }

    def 'write plugin data defined in properties files'() {
        setup:
        metaFilesPath.mkdirs()

        File pluginDefFile = new File(metaFilesPath, 'com.palantir.test-plugin1.properties')
        pluginDefFile << 'implementation-class=com.palantir.gradle.TestPlugin1'

        when:
        ExecutionResult result = runTasksSuccessfully('generatePomFileForPluginMavenPublication')

        then:
        result.wasExecuted('processResources')
        pomFile.exists()

        def xml = new XmlSlurper().parseText(pomFile.text)
        def propString = xml.properties[PublishGradlePluginUtils.PUBLISHED_PLUGIN_IDS_KEY].toString()
        List<GradlePluginDef> publishedPlugins = OBJECT_MAPPER.readValue(propString, GRADLE_PLUGIN_DEF_TYPE_REF)

        publishedPlugins[0].id == 'com.palantir.test-plugin1'
        publishedPlugins[0].implementingClass == 'com.palantir.gradle.TestPlugin1'
    }

    def 'existing properties are maintained and only one properties block is present'() {
        setup:
        metaFilesPath.mkdirs()
        File pluginDefFile = new File(metaFilesPath, 'com.palantir.test-plugin1.properties')
        pluginDefFile << 'implementation-class=com.palantir.gradle.TestPlugin1'

        //language=groovy
        buildFile << '''
            publishing {
                publications {
                    pluginMaven(MavenPublication) {
                        pom {
                            properties.put('existing-property', 'existing-value')
                        }
                    }
                }
            }
        '''.stripIndent(true)

        when:
        ExecutionResult result = runTasksSuccessfully('generatePomFileForPluginMavenPublication')

        then:
        result.wasExecuted('processResources')
        pomFile.exists()

        def xml = new XmlSlurper().parseText(pomFile.text)
        def propertiesBlock = xml.properties
        propertiesBlock.size() == 1 // Only one <properties> block

        // Both properties are present in the same block
        propertiesBlock.'existing-property'.text() == 'existing-value'
        def propString = propertiesBlock[PublishGradlePluginUtils.PUBLISHED_PLUGIN_IDS_KEY].text()
        List<GradlePluginDef> publishedPlugins = OBJECT_MAPPER.readValue(propString, GRADLE_PLUGIN_DEF_TYPE_REF)
        publishedPlugins[0].id == 'com.palantir.test-plugin1'
        publishedPlugins[0].implementingClass == 'com.palantir.gradle.TestPlugin1'
    }

}
