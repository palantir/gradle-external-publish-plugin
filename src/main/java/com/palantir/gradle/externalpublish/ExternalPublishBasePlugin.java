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

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import nebula.plugin.info.scm.ScmInfoPlugin;
import nebula.plugin.publishing.maven.MavenBasePublishPlugin;
import nebula.plugin.publishing.maven.MavenManifestPlugin;
import nebula.plugin.publishing.maven.MavenScmPlugin;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;

final class ExternalPublishBasePlugin implements Plugin<Project> {

    private final Set<String> sonatypePublicationNames = new HashSet<>();

    private Project project;

    @Override
    public void apply(Project projectVal) {
        this.project = projectVal;

        applyPublishingPlugins();
        linkWithRootProject();
        disableOtherPublicationsFromPublishingToSonatype();
        disableModuleMetadata();
        publishToMavenLocalAsPartOfBuild();

        // Sonatype requires we set a description on the pom, but the maven plugin will overwrite anything we set on
        // pom object. So we set the description on the project if it is not set, which the maven plugin reads from.
        if (project.getDescription() == null) {
            project.setDescription("Palantir open source project");
        }
    }

    private void applyPublishingPlugins() {
        // Intentionally not applying nebula.maven-publish, but most of its constituent plugins,
        // because we do _not_ want nebula.maven-compile-only
        Stream.of(
                        MavenPublishPlugin.class,
                        MavenBasePublishPlugin.class,
                        MavenManifestPlugin.class,
                        MavenScmPlugin.class,
                        ScmInfoPlugin.class)
                .forEach(plugin -> project.getPluginManager().apply(plugin));
    }

    private void linkWithRootProject() {
        if (project == project.getRootProject()) {
            // Can only do this on the root project as the Nexus plugin uses afterEvaluates which do not get run if
            // the root project has been evaluated, which happens before subproject evaluation
            project.getPluginManager().apply(ExternalPublishRootPlugin.class);
        }

        ExternalPublishRootPlugin rootPlugin = Optional.ofNullable(
                        project.getRootProject().getPlugins().findPlugin(ExternalPublishRootPlugin.class))
                .orElseThrow(() -> new GradleException(
                        "The com.palantir.external-publish plugin must be applied to the root project "
                                + "*before* this plugin is evaluated"));

        rootPlugin.sonatypeFinishingTask().ifPresent(sonatypeFinishingTask -> {
            project.getTasks().named("publish").configure(publish -> {
                publish.dependsOn(sonatypeFinishingTask);
            });
        });
    }

    private void disableOtherPublicationsFromPublishingToSonatype() {
        project.getTasks().withType(PublishToMavenRepository.class).configureEach(publishTask -> {
            publishTask.onlyIf(_ignored -> {
                if (publishTask.getRepository().getName().equals("sonatype")) {
                    boolean isSonatypePublish = sonatypePublicationNames.contains(
                            publishTask.getPublication().getName());

                    return isSonatypePublish && OurEnvironmentVariables.isTagBuild(project);
                }

                return true;
            });
        });
    }

    private void disableModuleMetadata() {
        // Turning off module metadata so that all consumers just use regular POMs
        project.getTasks()
                .withType(GenerateModuleMetadata.class)
                .configureEach(generateModuleMetadata -> generateModuleMetadata.setEnabled(false));
    }

    private void publishToMavenLocalAsPartOfBuild() {
        // This ensures we try out publishing and build all publishable artifacts at PR time before
        // merging into the main branch, rather than having these tasks fail at publish time.

        // Only run on circle node 0 to avoid repeating work on every circle node
        if (!OurEnvironmentVariables.environmentVariables(project)
                .isCircleNode0OrLocal()
                .get()) {
            return;
        }

        project.getPluginManager().apply(LifecycleBasePlugin.class);
        project.getTasks().named(LifecycleBasePlugin.BUILD_TASK_NAME).configure(build -> {
            TaskCollection<?> publishToMavenLocalsForOurPublications = project.getTasks()
                    .withType(PublishToMavenLocal.class)
                    .matching(publishToMavenLocal -> {
                        return sonatypePublicationNames.contains(
                                publishToMavenLocal.getPublication().getName());
                    });

            build.dependsOn(publishToMavenLocalsForOurPublications);
        });
    }

    public void addPublication(String publicationName, Action<MavenPublication> publicationConfiguration) {
        sonatypePublicationNames.add(publicationName);

        project.getExtensions().getByType(PublishingExtension.class).publications(publications -> {
            MavenPublication mavenPublication = publications.maybeCreate(publicationName, MavenPublication.class);
            publicationConfiguration.execute(mavenPublication);
            mavenPublication.pom(pom -> {
                pom.licenses(licenses -> {
                    licenses.license(license -> {
                        license.getName().set("The Apache License, Version 2.0");
                        license.getUrl().set("https://www.apache.org/licenses/LICENSE-2.0");
                    });
                });
                pom.developers(developers -> {
                    developers.developer(developer -> {
                        developer.getId().set("palantir");
                        developer.getName().set("Palantir Technologies Inc");
                        developer.getOrganizationUrl().set("https://www.palantir.com");
                    });
                });
            });
            signPublication(mavenPublication);
        });
    }

    private void signPublication(Publication publication) {
        GpgSigningKey.fromEnv(project).ifPresent(gpgSigningKey -> {
            project.getPluginManager().apply(SigningPlugin.class);

            SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
            signing.useInMemoryPgpKeys(gpgSigningKey.keyId(), gpgSigningKey.key(), gpgSigningKey.password());
            signing.sign(publication);
        });
    }

    public static ExternalPublishBasePlugin applyTo(Project project) {
        project.getPluginManager().apply(ExternalPublishBasePlugin.class);
        return project.getPlugins().findPlugin(ExternalPublishBasePlugin.class);
    }
}
