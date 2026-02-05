/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import com.palantir.logsafe.exceptions.SafeUncheckedIoException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class GenerateGradlePluginMetaDataTask extends DefaultTask {
    public static final String TASK_NAME = "generatePluginMetaData";
    public static final String IMPLEMENTATION_CLASS_KEY = "implementation-class";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public GenerateGradlePluginMetaDataTask() {
        getOutputFile().convention(getProject().getLayout().getBuildDirectory().file(getName() + ".json"));
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPluginDescriptorFiles();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Applies the plugin to the given project.
     */
    @TaskAction
    public void generate() {
        try {
            String result = OBJECT_MAPPER.writeValueAsString(getPluginDefs());
            Files.writeString(getOutputFile().get().getAsFile().toPath(), result);
        } catch (IOException e) {
            throw new SafeUncheckedIoException("Error writing plugin mapping", e);
        }
    }

    /**
     * Scan the plugin descriptor files and get the set of all plugin ids and implementing class names.
     */
    private Set<GradlePluginDef> getPluginDefs() {
        return getPluginDescriptorFiles().getFiles().stream()
                .filter(file -> file.getName().endsWith(".properties"))
                .map(file -> GradlePluginDef.of(getPluginId(file.toPath()), getImplementationClass(file.toPath())))
                .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(GradlePluginDef::id))));
    }

    /**
     * The plugin id is the base name of the file. e.g.
     * {@code com.palantir.some-plugin.properties}
     */
    private static String getPluginId(Path pluginDefFile) {
        String fileName = pluginDefFile.toFile().getName();
        int dotIndex = fileName.lastIndexOf('.');
        return fileName.substring(0, dotIndex);
    }

    /**
     * Get the implementing class value from the plugin descriptor file.  The file is of the form
     * {@code implementation-class=com.palantir.foo.PluginClass}
     */
    private static String getImplementationClass(Path pluginDefFile) {
        Properties pluginDesc = loadProperties(pluginDefFile);
        if (!pluginDesc.containsKey(IMPLEMENTATION_CLASS_KEY)) {
            throw new SafeRuntimeException("Could not find plugin impl class in file");
        }
        return pluginDesc.get(IMPLEMENTATION_CLASS_KEY).toString();
    }

    private static Properties loadProperties(Path path) {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new SafeUncheckedIoException("Error reading plugin descriptor file", e);
        }
        return properties;
    }

    /**
     * Load the result contents.
     */
    @Internal
    public String getMetaDataJson() {
        try {
            return Files.readString(getOutputFile().getAsFile().get().toPath());
        } catch (IOException e) {
            throw new SafeUncheckedIoException(e);
        }
    }
}
