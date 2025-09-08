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

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ExternalPublishBomPlugin implements Plugin<Project> {
    @Override
    public final void apply(Project project) {
        project.getPluginManager().apply("java-platform");
        //        project.getPluginManager().apply(MavenPublishPlugin.class);

        configurePlatformConstraints(project);

        ExternalPublishBasePlugin.applyTo(project)
                .addPublication(
                        "bom", maven -> maven.from(project.getComponents().getByName("javaPlatform")));
    }

    private void configurePlatformConstraints(Project project) {
        project.getDependencies().constraints(constraints -> {
            project.getRootProject().getSubprojects().forEach(subproject -> {
                if (!subproject.equals(project)) {
                    if (subproject.getPlugins().hasPlugin(ExternalPublishJarPlugin.class)) {
                        constraints.add("api", project.project(subproject.getPath()));
                        addPlatformToSubproject(subproject, project);
                    }
                }
            });
        });
    }

    private void addPlatformToSubproject(Project subproject, Project platformProject) {
        subproject.getDependencies().add("api", subproject.getDependencies().platform(platformProject));
    }
}
