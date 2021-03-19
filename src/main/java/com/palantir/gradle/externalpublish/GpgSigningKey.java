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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.gradle.api.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GpgSigningKey {
    private static final Logger log = LoggerFactory.getLogger(GpgSigningKey.class);

    private final String keyId;
    private final String key;
    private final String password;

    private GpgSigningKey(String keyId, String base64Key, String password) {
        this.keyId = keyId;
        this.key = new String(Base64.getDecoder().decode(base64Key), StandardCharsets.UTF_8);
        this.password = password;
    }

    public String keyId() {
        return keyId;
    }

    public String key() {
        return key;
    }

    public String password() {
        return password;
    }

    public static Optional<GpgSigningKey> fromEnv(Project project) {
        Optional<String> maybeKeyId = gpgKeyEnvVar(project, "GPG_SIGNING_KEY_ID");
        Optional<String> maybeBase64Key = gpgKeyEnvVar(project, "GPG_SIGNING_KEY");
        Optional<String> maybePassword = gpgKeyEnvVar(project, "GPG_SIGNING_KEY_PASSWORD");

        return maybeKeyId.flatMap(keyId ->
                maybeBase64Key.flatMap(key -> maybePassword.map(password -> new GpgSigningKey(keyId, key, password))));
    }

    private static Optional<String> gpgKeyEnvVar(Project project, String envVar) {
        Optional<String> value = EnvironmentVariables.envVarOrFromTestingProperty(project, envVar);

        boolean onCi = System.getenv("CI") != null;
        if (onCi && !value.isPresent()) {
            log.warn("Could not find environment variable {}, signing will be disabled", envVar);
        }

        return value;
    }
}
