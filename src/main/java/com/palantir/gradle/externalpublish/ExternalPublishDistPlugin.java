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

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ExternalPublishDistPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project project) {
        ExternalPublishBasePlugin.applyTo(project).addPublication("dist", publication -> {
            // Unfortunately need to use afterEvaluate here, since MavenPublication#artifact immediately tries to get
            // the value from the task provider, which will fail if the task has not yet been created.
            project.afterEvaluate(_ignored -> {
                publication.artifact(project.getTasks().named("distTar"));
            });
        });
    }
}
