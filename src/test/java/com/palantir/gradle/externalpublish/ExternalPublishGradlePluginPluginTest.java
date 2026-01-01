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

package com.palantir.gradle.externalpublish;

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.palantir.gradle.publish.GradlePluginDef;
import com.palantir.gradle.publish.GradlePluginMetaDataPlugin;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.files.gradle.GradleFile;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@GradlePluginTests
@DisabledConfigurationCache
class ExternalPublishGradlePluginPluginTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<List<GradlePluginDef>> GRADLE_PLUGIN_DEF_TYPE_REF =
            new TypeReference<List<GradlePluginDef>>() {};

    private Path buildDir;
    private Path pomFile;

    @BeforeEach
    void setup(RootProject rootProject) {
        buildDir = rootProject.buildDir().path().resolve("build");
        pomFile = buildDir.resolve("publications/pluginMaven/pom-default.xml");

        standardBuildFile(rootProject);
    }

    @SuppressWarnings("GradleTestPluginsBlock")
    GradleFile standardBuildFile(RootProject rootProject) {
        // Using buildscript block + apply plugin pattern to support external plugin-publish-plugin
        rootProject.buildGradle().prepend("""
            buildscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }

                dependencies {
                    classpath 'com.gradle.publish:plugin-publish-plugin:2.0.0'
                }
            }

            """).append("""
                apply plugin: 'com.palantir.external-publish'
                apply plugin: 'java-gradle-plugin'
                apply plugin: 'com.palantir.external-publish-gradle-plugin'

                group = 'com.palantir.test-palantir'
                version = '0.1.0'
                """);

        return rootProject.buildGradle();
    }

    @Test
    void writes_plugin_data_from_gradle_plugin_extension(GradleInvoker gradle, RootProject rootProject)
            throws Exception {
        rootProject.buildGradle().append("""
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
            """);

        InvocationResult result =
                gradle.withArgs("generatePomFileForPluginMavenPublication").buildsSuccessfully();

        assertThat(result).task(":processResources").succeeded();
        assertThat(result).task(":generatePluginMetaData").succeeded();
        rootProject
                .buildDir()
                .file("publications/pluginMaven/pom-default.xml")
                .assertThat()
                .exists();

        Document pomDoc = parseXmlFile(pomFile);
        String propString = getPropertyFromPom(pomDoc, GradlePluginMetaDataPlugin.PUBLISHED_PLUGIN_IDS_KEY);
        List<GradlePluginDef> publishedPlugins = OBJECT_MAPPER.readValue(propString, GRADLE_PLUGIN_DEF_TYPE_REF);

        assertThat(publishedPlugins.get(0).id()).isEqualTo("com.palantir.test-plugin1");
        assertThat(publishedPlugins.get(0).implementingClass()).isEqualTo("com.palantir.gradle.TestPlugin1");
        assertThat(publishedPlugins.get(1).id()).isEqualTo("com.palantir.test-plugin2");
        assertThat(publishedPlugins.get(1).implementingClass()).isEqualTo("com.palantir.gradle.TestPlugin2");
    }

    @Test
    void write_plugin_data_defined_in_properties_files(GradleInvoker gradle, RootProject rootProject) throws Exception {
        rootProject.directory("src/main/resources/META-INF/gradle-plugins").createDirectories();

        rootProject
                .file("src/main/resources/META-INF/gradle-plugins/com.palantir.test-plugin1.properties")
                .overwrite("implementation-class=com.palantir.gradle.TestPlugin1");

        InvocationResult result =
                gradle.withArgs("generatePomFileForPluginMavenPublication").buildsSuccessfully();

        assertThat(result).task(":processResources").succeeded();
        rootProject
                .buildDir()
                .file("publications/pluginMaven/pom-default.xml")
                .assertThat()
                .exists();

        Document pomDoc = parseXmlFile(pomFile);
        String propString = getPropertyFromPom(pomDoc, GradlePluginMetaDataPlugin.PUBLISHED_PLUGIN_IDS_KEY);
        List<GradlePluginDef> publishedPlugins = OBJECT_MAPPER.readValue(propString, GRADLE_PLUGIN_DEF_TYPE_REF);

        assertThat(publishedPlugins.get(0).id()).isEqualTo("com.palantir.test-plugin1");
        assertThat(publishedPlugins.get(0).implementingClass()).isEqualTo("com.palantir.gradle.TestPlugin1");
    }

    @Test
    void existing_properties_are_maintained_and_only_one_properties_block_is_present(
            GradleInvoker gradle, RootProject rootProject) throws Exception {
        rootProject.directory("src/main/resources/META-INF/gradle-plugins").createDirectories();

        rootProject
                .file("src/main/resources/META-INF/gradle-plugins/com.palantir.test-plugin1.properties")
                .overwrite("implementation-class=com.palantir.gradle.TestPlugin1");

        rootProject.buildGradle().append("""
            publishing {
                publications {
                    pluginMaven(MavenPublication) {
                        pom {
                            properties.put('existing-property', 'existing-value')
                        }
                    }
                }
            }
            """);

        InvocationResult result =
                gradle.withArgs("generatePomFileForPluginMavenPublication").buildsSuccessfully();

        assertThat(result).task(":processResources").succeeded();
        rootProject
                .buildDir()
                .file("publications/pluginMaven/pom-default.xml")
                .assertThat()
                .exists();

        Document pomDoc = parseXmlFile(pomFile);

        // Only one <properties> block
        NodeList propertiesElements = pomDoc.getElementsByTagName("properties");
        assertThat(propertiesElements.getLength()).isEqualTo(1);

        // Both properties are present in the same block
        assertThat(getPropertyFromPom(pomDoc, "existing-property")).isEqualTo("existing-value");

        String propString = getPropertyFromPom(pomDoc, GradlePluginMetaDataPlugin.PUBLISHED_PLUGIN_IDS_KEY);
        List<GradlePluginDef> publishedPlugins = OBJECT_MAPPER.readValue(propString, GRADLE_PLUGIN_DEF_TYPE_REF);

        assertThat(publishedPlugins.get(0).id()).isEqualTo("com.palantir.test-plugin1");
        assertThat(publishedPlugins.get(0).implementingClass()).isEqualTo("com.palantir.gradle.TestPlugin1");
    }

    private Document parseXmlFile(Path xmlFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(Files.newInputStream(xmlFile));
    }

    private Element getPropertiesElement(Document doc) {
        NodeList propertiesList = doc.getElementsByTagName("properties");
        if (propertiesList.getLength() == 0) {
            throw new IllegalStateException("No properties element found in POM");
        }
        return (Element) propertiesList.item(0);
    }

    private String getPropertyFromPom(Document doc, String propertyName) {
        Element propertiesElement = getPropertiesElement(doc);
        NodeList propertyElements = propertiesElement.getElementsByTagName(propertyName);
        if (propertyElements.getLength() == 0) {
            throw new IllegalStateException("Property '" + propertyName + "' not found in POM");
        }
        return propertyElements.item(0).getTextContent();
    }
}
