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

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.UnknownPluginException;

public class ExternalPublishConjurePlugin implements Plugin<Project> {
    private static final String CONJURE_PUBLISH_PLUGIN = "com.palantir.conjure-publish";

    @Override
    public final void apply(Project project) {
        try {
            project.getPluginManager().apply(CONJURE_PUBLISH_PLUGIN);
        } catch (UnknownPluginException e) {
            throw new GradleException("Could not find " + CONJURE_PUBLISH_PLUGIN + " to apply. "
                    + "Ensure you've added gradle-conjure as a buildscript dependency.");
        }

        ExternalPublishBasePlugin.applyTo(project).addPublication("conjure", _publication -> {});
    }
}
