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
import java.util.Objects;
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

        project.getTasks().withType(Jar.class).named("jar").configure(jar -> {
            jar.getManifest()
                    .attributes(
                            Collections.singletonMap("Implementation-Version", new ProjectVersionToString(project)));
        });

        JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        javaPluginExtension.withJavadocJar();
        javaPluginExtension.withSourcesJar();
    }

    /**
     * This is effectively a provider for the project version string value. The jar manifest may be configured
     * before project versions have been set, particularly for subprojects which are configured via 'allprojects'
     * or 'subprojects'.
     */
    private static final class ProjectVersionToString {
        private final Project project;

        private ProjectVersionToString(Project project) {
            this.project = project;
        }

        @Override
        public String toString() {
            return Objects.toString(project.getVersion());
        }
    }
}
