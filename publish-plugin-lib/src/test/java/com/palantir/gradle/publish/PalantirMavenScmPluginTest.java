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

package com.palantir.gradle.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PalantirMavenScmPluginTest {
    @ParameterizedTest
    @MethodSource("expected_urls")
    void calculates_correct_https_urls_from_origin(TestCase testCase) {
        assertThat(PalantirMavenScmPlugin.calculateUrlFromOriginUrl(testCase.input))
                .isEqualTo(testCase.expectedOutput);
    }

    static Stream<TestCase> expected_urls() {
        return Stream.of(
                // HTTPS URLs
                new TestCase("https://github.com/palantir/repo.git", "https://github.com/palantir/repo"),
                new TestCase("https://github.com/palantir/repo.git/", "https://github.com/palantir/repo"),
                new TestCase("https://gitlab.com/org/repo.git", "https://gitlab.com/org/repo"),
                new TestCase("https://gitlab.com/org/repo.git/", "https://gitlab.com/org/repo"),
                new TestCase("https://foobar@github.com/palantir/repo.git", "https://github.com/palantir/repo"),
                // SSH URLs (git@ style)
                new TestCase("git@github.com:palantir/repo.git", "https://github.com/palantir/repo"),
                new TestCase("git@github.com:palantir/repo.git/", "https://github.com/palantir/repo"),
                new TestCase("git@gitlab.com:org/repo.git", "https://gitlab.com/org/repo"),
                new TestCase("git@github.com:palantir/repo", "https://github.com/palantir/repo"),
                new TestCase("git@gitlab.com:org/repo.git/", "https://gitlab.com/org/repo"),
                // SSH URLs (ssh:// style)
                new TestCase("ssh://git@github.com/palantir/repo.git", "https://github.com/palantir/repo"),
                new TestCase("ssh://github.com/palantir/repo.git", "https://github.com/palantir/repo"),
                // Enterprise GitHub (subdomains)
                new TestCase("https://github.example.com/org/repo.git", "https://github.example.com/org/repo"),
                new TestCase("git@github.example.com:org/repo.git", "https://github.example.com/org/repo"),
                // Invalid URL passthrough
                new TestCase("invalid_url", "invalid_url"),
                // GitLab nested groups/subgroups
                new TestCase("git@gitlab.com:org/subgroup/repo.git", "https://gitlab.com/org/subgroup/repo"),
                new TestCase("https://gitlab.com/org/subgroup/repo.git", "https://gitlab.com/org/subgroup/repo"));
    }

    private static final class TestCase {
        private final String input;
        private final String expectedOutput;

        TestCase(String input, String expectedOutput) {
            this.input = input;
            this.expectedOutput = expectedOutput;
        }

        @Override
        public String toString() {
            return String.format("%s -> %s", input, expectedOutput);
        }
    }
}
