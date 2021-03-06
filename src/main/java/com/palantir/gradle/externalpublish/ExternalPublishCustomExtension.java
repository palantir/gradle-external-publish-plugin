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

import groovy.lang.Closure;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.publish.maven.MavenPublication;

public class ExternalPublishCustomExtension {
    private final PublicationAdder publicationAdder;

    @Inject
    public ExternalPublishCustomExtension(PublicationAdder publicationAdder) {
        this.publicationAdder = publicationAdder;
    }

    public final void publication(String name, Closure<Void> closure) {
        publicationAdder.addPublication(name, mavenPublication -> {
            closure.setDelegate(mavenPublication);
            closure.call();
        });
    }

    interface PublicationAdder {
        void addPublication(String publicationName, Action<MavenPublication> configurator);
    }
}
