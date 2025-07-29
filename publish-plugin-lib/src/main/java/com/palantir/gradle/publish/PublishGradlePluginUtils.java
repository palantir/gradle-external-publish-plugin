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

package com.palantir.gradle.publish;

import groovy.util.Node;
import groovy.util.NodeList;
import java.util.Optional;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;

public final class PublishGradlePluginUtils {
    public static final String PUBLISHED_PLUGIN_IDS_KEY = "published-gradle-plugins";

    /**
     * Writes the id and classname for any gradle plugin published by this project to the pom.  This
     * lets our code analytics pipelines connect a gradle plugin id back to the source repository that published it.
     *
     * The JavaGradlePluginPlugin adds a CopySpec to processResources for plugin descriptors that are created from the
     * gradlePlugins extension (via the pluginDescriptors task).  So once processResources is done copying plugin
     * descriptors from the normal src/main/resources/META-INF/gradle-plugins directory and from the generated
     * descriptors directory, we know all the descriptors files are in place, and we can add the information about them
     * to the pom.
     *
     * @see <a href=https://github.com/gradle/gradle/blob/9bfee9d10dfad5011e2fbd066de43e86166b5b53/platforms/extensibility/plugin-development/src/main/java/org/gradle/plugin/devel/plugins/JavaGradlePluginPlugin.java#L248-L259>JavaGradlePluginPlugin</a>
     * @see <a href=https://github.com/gradle/gradle/blob/9bfee9d10dfad5011e2fbd066de43e86166b5b53/platforms/software/maven/src/main/java/org/gradle/api/publish/maven/plugins/MavenPublishPlugin.java#L201-L212>MavenPublishPlugin</a>
     */
    public static void writePomMetadata(Project project) {
        // Access the JavaPluginExtension to get SourceSets
        JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSetContainer sourceSets = javaPluginExtension.getSourceSets();
        SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        TaskProvider<ProcessResources> processResources =
                project.getTasks().named(mainSourceSet.getProcessResourcesTaskName(), ProcessResources.class);
        DirectoryProperty resourcesDirectory = project.getObjects()
                .directoryProperty()
                .fileProvider(processResources.map(ProcessResources::getDestinationDir));

        TaskProvider<GenerateGradlePluginMetaDataTask> generatePluginMdTask = project.getTasks()
                .register(
                        GenerateGradlePluginMetaDataTask.TASK_NAME,
                        GenerateGradlePluginMetaDataTask.class,
                        task -> task.getPluginDescDirectory()
                                .set(resourcesDirectory.map(resources -> resources.dir("META-INF/gradle-plugins"))));

        // Because the GeneratePom task is an UntrackedTask, it doesn't know that another task needs to run in order
        // to provide all of its inputs.  So we have to force the dependsOn.
        project.afterEvaluate(_ignored -> {
            project.getTasks().withType(GenerateMavenPom.class).configureEach(generatePom -> {
                generatePom.dependsOn(generatePluginMdTask);
            });
        });

        project.getExtensions().getByType(PublishingExtension.class).publications(publications -> publications
                .withType(MavenPublication.class)
                .configureEach(mavenPublication -> {
                    // We should be using getProperties here but currently the maven-publish plugin is not fully
                    // configuration-cache friendly. This should be reverted to getProperties once this gradle issue
                    // is resolved: https://github.com/gradle/gradle/issues/23397
                    mavenPublication.getPom().withXml(xmlProvider -> {
                        Node pomNode = xmlProvider.asNode();
                        Optional.of((NodeList) pomNode.get("properties"))
                                .filter(nodes -> !nodes.isEmpty())
                                .map(nodes -> (Node) nodes.get(0))
                                .orElseGet(() -> pomNode.appendNode("properties"))
                                .appendNode(
                                        PUBLISHED_PLUGIN_IDS_KEY,
                                        generatePluginMdTask.get().getMetaDataJson());
                    });
                }));
    }

    private PublishGradlePluginUtils() {}
}
