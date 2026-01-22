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
import static org.assertj.core.groups.Tuple.tuple;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.palantir.gradle.publish.GradlePluginDef;
import com.palantir.gradle.publish.GradlePluginMetaDataPlugin;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@DisabledConfigurationCache
class ExternalPublishGradlePluginPluginTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final XmlMapper XML_MAPPER = XmlMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private static final TypeReference<List<GradlePluginDef>> GRADLE_PLUGIN_DEF_TYPE_REF = new TypeReference<>() {};

    @BeforeEach
    void setup(RootProject rootProject) {
        rootProject
                .buildGradle()
                .plugins()
                .add("com.palantir.external-publish")
                .add("java-gradle-plugin")
                .add("com.palantir.external-publish-gradle-plugin");
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

        PomProject pom =
                parsePomFile(rootProject.buildDir().path().resolve("publications/pluginMaven/pom-default.xml"));
        List<GradlePluginDef> publishedPlugins =
                OBJECT_MAPPER.readValue(pom.properties().publishedPluginIds(), GRADLE_PLUGIN_DEF_TYPE_REF);

        assertThat(publishedPlugins)
                .extracting(GradlePluginDef::id, GradlePluginDef::implementingClass)
                .containsExactly(
                        tuple("com.palantir.test-plugin1", "com.palantir.gradle.TestPlugin1"),
                        tuple("com.palantir.test-plugin2", "com.palantir.gradle.TestPlugin2"));
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

        PomProject pom =
                parsePomFile(rootProject.buildDir().path().resolve("publications/pluginMaven/pom-default.xml"));
        List<GradlePluginDef> publishedPlugins =
                OBJECT_MAPPER.readValue(pom.properties().publishedPluginIds(), GRADLE_PLUGIN_DEF_TYPE_REF);

        assertThat(publishedPlugins)
                .extracting(GradlePluginDef::id, GradlePluginDef::implementingClass)
                .containsExactly(tuple("com.palantir.test-plugin1", "com.palantir.gradle.TestPlugin1"));
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

        PomProject pom =
                parsePomFile(rootProject.buildDir().path().resolve("publications/pluginMaven/pom-default.xml"));

        assertThat(pom.properties().existingProperty())
                .as("Both properties are present")
                .isEqualTo("existing-value");

        List<GradlePluginDef> publishedPlugins =
                OBJECT_MAPPER.readValue(pom.properties().publishedPluginIds(), GRADLE_PLUGIN_DEF_TYPE_REF);

        assertThat(publishedPlugins)
                .extracting(GradlePluginDef::id, GradlePluginDef::implementingClass)
                .containsExactly(tuple("com.palantir.test-plugin1", "com.palantir.gradle.TestPlugin1"));
    }

    private PomProject parsePomFile(Path xmlFile) throws Exception {
        return XML_MAPPER.readValue(xmlFile.toFile(), PomProject.class);
    }

    private record PomProject(PomProperties properties) {}

    private record PomProperties(
            @JacksonXmlProperty(localName = GradlePluginMetaDataPlugin.PUBLISHED_PLUGIN_IDS_KEY)
            String publishedPluginIds,

            @JacksonXmlProperty(localName = "existing-property")
            String existingProperty) {}
}
