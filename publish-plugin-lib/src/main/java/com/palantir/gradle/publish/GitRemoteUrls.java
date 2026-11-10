/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.provider.Provider;

/**
 * Utilities for converting git remote URLs (e.g. {@code git@host:org/repo.git}, {@code ssh://git@host/org/repo.git},
 * {@code https://host/org/repo.git}) into browsable {@code https://host/org/repo} URLs.
 */
public final class GitRemoteUrls {

    private static final Pattern REMOTE_URL_PATTERN = Pattern.compile(
            "((git|ssh|https?):(//))?(\\w+@)?(?<domainOwner>[\\w.@\\-~]+)([:\\\\/])(?<repo>[\\w.@:/\\-~]+?)"
                    + "(\\.git)?(/)?");

    /**
     * Converts a git remote URL into a browsable https URL.
     *
     * @return the https URL, or empty if the remote URL could not be parsed
     */
    public static Optional<String> toHttpsUrl(String remoteUrl) {
        Matcher matcher = REMOTE_URL_PATTERN.matcher(remoteUrl);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(String.format("https://%s/%s", matcher.group("domainOwner"), matcher.group("repo")));
    }

    /**
     * Lazily converts a git remote URL into a browsable https URL.
     *
     * @return a provider of the https URL, which has no value if the remote URL is absent or could not be parsed
     */
    public static Provider<String> toHttpsUrl(Provider<String> remoteUrl) {
        return remoteUrl.map(url -> toHttpsUrl(url).orElse(null));
    }

    private GitRemoteUrls() {}
}
