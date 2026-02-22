/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.oap.server.library.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Same-FQCN replacement for native-image compatibility.
 *
 * <p>In the JVM distro, the {@code config/} directory is on the classpath,
 * so {@code ClassLoader.getResource()} finds YAML/config files directly.
 * In a native image there is no classpath — this replacement tries the
 * filesystem first ({@code config/} relative to working directory),
 * then falls back to embedded classpath resources.
 */
public class ResourceUtils {

    private static final String CONFIG_DIR = "config";

    public static Reader read(String fileName) throws FileNotFoundException {
        return new InputStreamReader(readToStream(fileName), StandardCharsets.UTF_8);
    }

    public static InputStream readToStream(String fileName) throws FileNotFoundException {
        // 1. Try filesystem: config/<fileName>
        File configFile = new File(CONFIG_DIR, fileName);
        if (configFile.isFile()) {
            return new FileInputStream(configFile);
        }

        // 2. Try filesystem: direct path (for absolute or already-qualified names)
        File directFile = new File(fileName);
        if (directFile.isFile()) {
            return new FileInputStream(directFile);
        }

        // 3. Fall back to classpath (embedded resources in native image)
        URL url = ResourceUtils.class.getClassLoader().getResource(fileName);
        if (url == null) {
            throw new FileNotFoundException("file not found: " + fileName);
        }
        return ResourceUtils.class.getClassLoader().getResourceAsStream(fileName);
    }

    public static File[] getPathFiles(String path) throws FileNotFoundException {
        // 1. Try filesystem: config/<path>
        File configDir = new File(CONFIG_DIR, path);
        if (configDir.isDirectory()) {
            return Arrays.stream(Objects.requireNonNull(configDir.listFiles(), "No files in " + path))
                         .filter(File::isFile).toArray(File[]::new);
        }

        // 2. Fall back to classpath
        URL url = ResourceUtils.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new FileNotFoundException("path not found: " + path);
        }
        return Arrays.stream(Objects.requireNonNull(new File(url.getPath()).listFiles(), "No files in " + path))
                     .filter(File::isFile).toArray(File[]::new);
    }

    public static List<File> getDirectoryFilesRecursive(String directoryPath,
                                                        int maxDepth) throws FileNotFoundException {
        // 1. Try filesystem: config/<directoryPath>
        File configDir = new File(CONFIG_DIR, directoryPath);
        if (configDir.isDirectory()) {
            List<File> fileList = new ArrayList<>();
            return getDirectoryFilesRecursive(configDir.getPath(), fileList, maxDepth);
        }

        // 2. Fall back to classpath
        URL url = ResourceUtils.class.getClassLoader().getResource(directoryPath);
        if (url == null) {
            throw new FileNotFoundException("path not found: " + directoryPath);
        }
        List<File> fileList = new ArrayList<>();
        return getDirectoryFilesRecursive(url.getPath(), fileList, maxDepth);
    }

    private static List<File> getDirectoryFilesRecursive(String directoryPath, List<File> fileList, int maxDepth) {
        if (maxDepth < 0) {
            return fileList;
        }
        maxDepth--;
        File file = new File(directoryPath);
        if (file.isDirectory()) {
            File[] subFiles = file.listFiles();
            if (subFiles != null) {
                for (File subFile : subFiles) {
                    if (subFile.isDirectory()) {
                        getDirectoryFilesRecursive(subFile.getPath(), fileList, maxDepth);
                    } else {
                        fileList.add(subFile);
                    }
                }
            }
        }
        return fileList;
    }

    public static Path getPath(String path) {
        // 1. Try filesystem: config/<path>
        File configFile = new File(CONFIG_DIR, path);
        if (configFile.exists()) {
            return configFile.toPath();
        }

        // 2. Fall back to classpath
        return new File(Objects.requireNonNull(ResourceUtils.class.getClassLoader().getResource(path)).getPath()).toPath();
    }
}
