/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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

import java.lang.reflect.InvocationTargetException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.jetbrains.intellij.IntelliJPlugin;
import org.jetbrains.intellij.tasks.BuildPluginTask;
import org.jetbrains.intellij.tasks.PatchPluginXmlTask;
import org.jetbrains.intellij.tasks.PublishPluginTask;

public class ExternalPublishIntellijPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project project) {

        project.getPlugins().apply(LifecycleBasePlugin.class);
        project.getPlugins().apply(IntelliJPlugin.class);

        TaskProvider<PublishPluginTask> publishPlugin =
                project.getTasks().named("publishPlugin", PublishPluginTask.class);

        TaskProvider<BuildPluginTask> buildPlugin = project.getTasks().named("buildPlugin", BuildPluginTask.class);

        ExternalPublishBasePlugin.applyTo(project).addPublication("intellij", publication -> {
            publication.artifact(buildPlugin);
        });

        project.getTasks().named("patchPluginXml", PatchPluginXmlTask.class).configure(task -> {
            task.getVersion().set(project.provider(() -> project.getVersion().toString()));
        });

        project.getTasks().withType(JavaExec.class).named("runIde", task -> {
            // allows for debugging
            task.jvmArgs("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED");
            task.dependsOn(buildPlugin);
        });

        project.afterEvaluate(projectAfterEvaluate -> {
            projectAfterEvaluate.getTasks().withType(JavaExec.class).named("runIde", task -> {
                task.getJavaLauncher().set((JavaLauncher) null);
            });
        });

        publishPlugin.configure(task -> {
            task.onlyIf(_ignored -> OurEnvironmentVariables.isTagBuild(project));
            task.getToken().set(System.getenv("JETBRAINS_PLUGIN_REPO_TOKEN"));
        });

        project.getTasks().named("publish", task -> {
            task.dependsOn(publishPlugin);
        });

        project.getTasks().named("check", task -> {
            task.dependsOn(buildPlugin, project.getTasks().named("verifyPlugin"));
        });

        project.getTasks().named("buildSearchableOptions", task -> {
            task.setEnabled(false);
        });

        // We are using reflection to call the correct methods. This avoids having a direct dependency on GCV, which is
        // sometimes using in `plugins {` blocks so is in a different classloader, making configuring using Java
        // correctly without ClassCastExceptions exceedingly difficult.
        project.getRootProject().getPlugins().withId("com.palantir.versions-lock", _ignored -> {
            project.getExtensions().configure("versionsLock", versionsLock -> {
                // 'org.jetbrains.intellij' creates a dependency on *IntelliJ*, which GCV cannot resolve
                try {
                    versionsLock
                            .getClass()
                            .getMethod("disableJavaPluginDefaults")
                            .invoke(versionsLock);
                } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        project.getRootProject().getExtensions().configure("versionRecommendations", versionRecommendations -> {
            try {
                versionRecommendations
                        .getClass()
                        .getMethod("excludeConfigurations", String[].class)
                        .invoke(versionRecommendations, (Object) new String[] {"idea"});
            } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
