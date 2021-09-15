/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

import java.util.Optional;
import org.gradle.api.Project;

final class EnvironmentVariables {
    private EnvironmentVariables() {}

    static Optional<String> envVarOrFromTestingProperty(Project project, String envVar) {
        boolean isTesting =
                Optional.ofNullable((String) project.findProperty("__TESTING")).equals(Optional.of("true"));

        if (isTesting) {
            return Optional.ofNullable((String) project.findProperty("__TESTING_" + envVar));
        }

        return Optional.ofNullable(System.getenv(envVar));
    }

    static boolean isTagBuild(Project project) {
        return envVarOrFromTestingProperty(project, "CIRCLE_TAG")
                .filter(tag -> !tag.isEmpty())
                .isPresent();
    }

    static boolean isFork(Project project) {
        return envVarOrFromTestingProperty(project, "CIRCLE_PR_USERNAME").isPresent();
    }
}
