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

import java.nio.charset.StandardCharsets;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.application.CreateStartScripts;
import org.gradle.api.tasks.bundling.Compression;
import org.gradle.api.tasks.bundling.Tar;
import org.gradle.util.GFileUtils;

public class ExternalPublishApplicationDistPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project project) {
        configureApplicationDist(project);

        project.getPluginManager().apply(ExternalPublishDistPlugin.class);
    }

    private static void configureApplicationDist(Project project) {
        project.getPluginManager().apply("application");

        project.getTasks()
                .withType(Tar.class)
                .named("distTar")
                .configure(distTar -> distTar.setCompression(Compression.GZIP));

        project.getTasks().withType(CreateStartScripts.class).configureEach(createStartScripts -> {
            createStartScripts.doLast(new FixWindowsStartScripts());
        });
    }

    /**
     * Windows has a very short length limit on commands, and lines in a batch file are commands. So instead of listing
     * the whole classpath explicitly, which can overflow this tiny limit, we just refer to the lib directory as the
     * classpath.
     */
    private static final class FixWindowsStartScripts implements Action<Task> {
        @Override
        public void execute(Task task) {
            CreateStartScripts createStartScripts = (CreateStartScripts) task;

            String windowsScript = GFileUtils.readFile(createStartScripts.getWindowsScript());
            String modified = windowsScript.replaceFirst("(set CLASSPATH=%APP_HOME%\\\\lib\\\\).*", "$1*");
            GFileUtils.writeFile(modified, createStartScripts.getWindowsScript(), StandardCharsets.UTF_8.toString());
        }
    }
}
