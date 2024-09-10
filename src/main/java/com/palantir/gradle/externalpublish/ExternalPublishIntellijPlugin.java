package com.palantir.gradle.externalpublish;

import java.lang.reflect.InvocationTargetException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.jetbrains.intellij.tasks.BuildPluginTask;
import org.jetbrains.intellij.tasks.PatchPluginXmlTask;
import org.jetbrains.intellij.tasks.PublishPluginTask;

public class ExternalPublishIntellijPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project project) {

        project.getPlugins().apply("org.jetbrains.intellij");

        TaskProvider<PublishPluginTask> publishPlugin =
                project.getTasks().named("publishPlugin", PublishPluginTask.class);

        TaskProvider<BuildPluginTask> buildPlugin = project.getTasks().named("buildPlugin", BuildPluginTask.class);

        TaskProvider<PatchPluginXmlTask> patchPluginXml =
                project.getTasks().named("patchPluginXml", PatchPluginXmlTask.class);

        patchPluginXml.configure(task -> {
            task.getVersion().set(project.getVersion().toString());
        });

        project.getTasks().withType(JavaExec.class).named("runIde", task -> {
            task.jvmArgs("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED");
            task.dependsOn(buildPlugin);
        });

        project.afterEvaluate(projectAfterEvaluate -> {
            projectAfterEvaluate.getTasks().withType(JavaExec.class).named("runIde", task -> {
                task.getJavaLauncher().set((JavaLauncher) null);
            });
        });

        publishPlugin.configure(task -> {
            task.onlyIf(_ignored -> OurEnvironmentVariables.isTagBuild(project));
            task.getToken().set(System.getenv("JETBRAINS_PLUGIN_REPO_TOKEN"));
        });

        project.afterEvaluate(_ignored -> {
            project.getTasks().named("publish", task -> {
                task.dependsOn(publishPlugin);
            });
        });

        project.getTasks().named("check", task -> {
            task.dependsOn(buildPlugin, project.getTasks().named("verifyPlugin"));
        });

        project.getTasks().named("buildSearchableOptions", task -> {
            task.setEnabled(false);
        });

        project.getRootProject().getPlugins().withId("com.palantir.versions-lock", _ignored -> {
            project.getExtensions().configure("versionsLock", versionsLock -> {
                // 'org.jetbrains.intellij' creates a dependency on *IntelliJ*, which GCV cannot resolve
                try {
                    versionsLock
                            .getClass()
                            .getMethod("disableJavaPluginDefaults")
                            .invoke(versionsLock);
                } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        ExternalPublishBasePlugin.applyTo(project).addPublication("intellij", publication -> {
            publication.artifact(buildPlugin);
        });
    }
}
