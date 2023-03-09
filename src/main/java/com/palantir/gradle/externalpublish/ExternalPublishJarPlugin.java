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

import java.util.Collections;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.tasks.Jar;

public class ExternalPublishJarPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project project) {
        configureJars(project);

        ExternalPublishBasePlugin.applyTo(project)
                .addPublication(
                        "maven", maven -> maven.from(project.getComponents().getByName("java")));
    }

    private static void configureJars(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        // Update the manifest Implementation-Version attribute after the project is evaluated, otherwise
        // project version information may not be available yet.
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project proj) {
                proj.getTasks().withType(Jar.class).named("jar").configure(jar -> {
                    jar.getManifest().attributes(Collections.singletonMap("Implementation-Version", proj.getVersion()));
                });
            }
        });

        JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        javaPluginExtension.withJavadocJar();
        javaPluginExtension.withSourcesJar();
    }
}
