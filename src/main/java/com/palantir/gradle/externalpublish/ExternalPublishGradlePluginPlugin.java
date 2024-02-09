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

import com.palantir.gradle.utils.environmentvariables.EnvironmentVariables;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.UnknownPluginException;
import org.gradle.api.tasks.TaskProvider;

public class ExternalPublishGradlePluginPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project project) {
        project.getPluginManager().apply("java-gradle-plugin");
        // So we can get the publish task
        project.getPluginManager().apply("publishing");

        try {
            project.getPluginManager().apply("com.gradle.plugin-publish");
        } catch (UnknownPluginException e) {
            throw new GradleException("Could not find com.gradle.plugin-publish - ensure you have com.gradle"
                    + ".publish:plugin-publish-plugin on your buildscript classpath");
        }

        TaskProvider<?> publishPluginsTask = project.getTasks().named("publishPlugins");
        project.getTasks().named("publish").configure(publish -> publish.dependsOn(publishPluginsTask));

        publishPluginsTask.configure(publishPlugins -> {
            publishPlugins.onlyIf(_ignored -> OurEnvironmentVariables.isTagBuild(project));
        });

        EnvironmentVariables envVars = OurEnvironmentVariables.environmentVariables(project);

        ExtraPropertiesExtension extraProperties = project.getExtensions().getExtraProperties();
        extraProperties.set(
                "gradle.publish.key",
                envVars.envVarOrFromTestingProperty("GRADLE_KEY").getOrNull());
        extraProperties.set(
                "gradle.publish.secret",
                envVars.envVarOrFromTestingProperty("GRADLE_SECRET").getOrNull());
    }
}
