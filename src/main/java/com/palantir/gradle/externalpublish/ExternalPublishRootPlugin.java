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

import io.github.gradlenexus.publishplugin.NexusPublishExtension;
import io.github.gradlenexus.publishplugin.NexusPublishPlugin;
import java.time.Duration;
import java.util.Optional;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

public class ExternalPublishRootPlugin implements Plugin<Project> {
    private Project rootProject;

    @Override
    public final void apply(Project rootProjectVal) {
        this.rootProject = rootProjectVal;

        if (rootProject != rootProject.getRootProject()) {
            throw new GradleException("The " + ExternalPublishRootPlugin.class.getSimpleName()
                    + " plugin must be applied on the root project");
        }

        rootProject.getPluginManager().apply(NexusPublishPlugin.class);
        NexusPublishExtension publishExtension = rootProject.getExtensions().getByType(NexusPublishExtension.class);

        // It can take 20 mins to close a Sonatype repo, even if there is only 1 artifact :o
        publishExtension.getConnectTimeout().set(Duration.ofMinutes(25));
        publishExtension.getClientTimeout().set(Duration.ofMinutes(25));

        publishExtension.getRepositories().sonatype(repo -> {
            repo.getUsername().set(System.getenv("SONATYPE_USERNAME"));
            repo.getPassword().set(System.getenv("SONATYPE_PASSWORD"));
        });

        TaskProvider<?> checkSigningKey = rootProject.getTasks().register("checkSigningKey", CheckSigningKeyTask.class);
        TaskProvider<?> checkVersion = rootProject.getTasks().register("checkVersion", CheckVersionTask.class);

        rootProject.getTasks().named("initializeSonatypeStagingRepository").configure(initialize -> {
            initialize.dependsOn(checkSigningKey, checkVersion);
        });

        rootProject
                .getTasks()
                .named("closeSonatypeStagingRepository")
                .configure(CircleCiContextDeadlineAvoidance::avoidHittingCircleCiContextDeadlineByPrintingEverySoOften);
    }

    public final Optional<TaskProvider<?>> sonatypeFinishingTask() {
        boolean isTagBuild = EnvironmentVariables.isTagBuild(rootProject);

        if (!isTagBuild) {
            return Optional.empty();
        }

        return Optional.of(rootProject.getTasks().named("closeAndReleaseSonatypeStagingRepository"));
    }
}
