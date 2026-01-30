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

import com.google.common.base.Strings;
import groovy.util.Node;
import groovy.util.NodeList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.process.ExecOutput;

/**
 * A replacement for nebula's MavenScmPlugin that correctly converts SSH git remotes to HTTPS URLs
 * in the POM file. The nebula plugin logs warnings when encountering SSH remotes; this plugin
 * handles them properly by converting git@host:org/repo style URLs to https://host/org/repo.
 */
public abstract class PalantirMavenScmPlugin implements Plugin<Project> {

    private static final Logger log = Logging.getLogger(PalantirMavenScmPlugin.class);

    private static final Object SCM_BRANCH_KEY = "originBranch";

    private static final Pattern ORIGIN_URL_PATTERN = Pattern.compile(
            "((git|ssh|https?):(//))?(\\w+@)?(?<domainOwner>[\\w.@\\-~]+)([:\\\\/])(?<repo>[\\w.@:/\\-~]+?)"
                    + "(\\.git)?(/)?");

    @Inject
    @SuppressWarnings("JavaxInjectOnAbstractMethod") // Gradle's recommended pattern for service injection in plugins
    protected abstract ProviderFactory getProviderFactory();

    @Override
    public final void apply(Project project) {
        project.getPlugins().apply(MavenPublishPlugin.class);

        if (insideTmpDir(project.getRootDir())) {
            return;
        }

        Provider<String> originUrl = getOriginUrl(project.getRootDir());
        Provider<String> branch = getBranch(project.getRootDir());

        project.getExtensions().getByType(PublishingExtension.class).publications(publications -> publications
                .withType(MavenPublication.class)
                .configureEach(mavenPublication -> mavenPublication.pom(mavenPom -> {
                    mavenPom.getUrl().set(originUrl.map(PalantirMavenScmPlugin::calculateUrlFromOriginUrl));
                    mavenPom.scm(mavenPomScm -> mavenPomScm.getUrl().set(originUrl));
                    mavenPom.withXml(xmlProvider -> {
                        Node rootNode = xmlProvider.asNode();
                        NodeList propertiesList = (NodeList) rootNode.get("properties");
                        Node propertiesNode = propertiesList.isEmpty()
                                ? rootNode.appendNode("properties")
                                : (Node) propertiesList.get(0);
                        propertiesNode.appendNode(SCM_BRANCH_KEY, branch.get());
                    });
                })));
    }

    private Provider<String> getOriginUrl(File rootDir) {
        return runCommand(rootDir, "git", "config", "remote.origin.url");
    }

    private Provider<String> getBranch(File rootDir) {
        return runCommand(rootDir, "git", "branch", "--show", "current")
                .map(Strings::emptyToNull)
                .orElse(runCommand(rootDir, "git", "rev-parse", "HEAD"));
    }

    private Provider<String> runCommand(File rootDir, String... command) {
        ExecOutput output = getProviderFactory().exec(execSpec -> {
            execSpec.commandLine((Object[]) command);
            execSpec.workingDir(rootDir);
            execSpec.setIgnoreExitValue(true);
        });

        record OutputStreams(String stdOut, String stdErr) {}

        Provider<OutputStreams> outputStreams = output.getStandardOutput()
                .getAsText()
                .zip(output.getStandardError().getAsText(), OutputStreams::new);

        return output.getResult().zip(outputStreams, (result, streams) -> {
            if (result.getExitValue() != 0) {
                throw new RuntimeException(String.format(
                        Locale.ROOT, "Exited with code %d:\n\n%s", result.getExitValue(), streams.stdErr()));
            }
            return streams.stdOut().trim();
        });
    }

    static String calculateUrlFromOriginUrl(String originUrl) {
        Matcher matcher = ORIGIN_URL_PATTERN.matcher(originUrl);

        if (matcher.matches()) {
            return String.format("https://%s/%s", matcher.group("domainOwner"), matcher.group("repo"));
        } else {
            return originUrl;
        }
    }

    private static boolean insideTmpDir(File rootDir) {
        // Some repos roll their own testing using junit, like gradle-baseline. These put the gradle build in a
        // temporary directory instead of build.

        try {
            // We must use toRealPath() here as macos has a symlink from /var -> /private/var
            Path canonicalTmpDir =
                    Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
            Path canonicalRootDir = rootDir.toPath().toRealPath();

            return canonicalRootDir.startsWith(canonicalTmpDir);
        } catch (IOException e) {
            // Don't fail the build because of this, as this is the prime way to make it say only fail in the
            // excavator execution environment - just make an obnoxious log message
            log.error("Failed to determine if build is inside tmp dir", e);
            return false;
        }
    }
}
