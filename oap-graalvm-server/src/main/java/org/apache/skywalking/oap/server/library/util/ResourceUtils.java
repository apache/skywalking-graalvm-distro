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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ProcessProperties;

/**
 * Same-FQCN replacement: a native image has no class path directory, so it reads its configuration from the
 * {@code config/} directory next to its executable, and from nothing else. On the JVM the lookup is upstream's, the
 * class path, where the JVM distribution puts its {@code config/}.
 */
public class ResourceUtils {
    private static final String CONFIG_DIR = "config";

    public static Reader read(String fileName) throws FileNotFoundException {
        return new InputStreamReader(readToStream(fileName), StandardCharsets.UTF_8);
    }

    public static InputStream readToStream(String fileName) throws FileNotFoundException {
        return readToStream(configDirectory(), fileName);
    }

    static InputStream readToStream(Path configDirectory, String fileName) throws FileNotFoundException {
        if (configDirectory != null) {
            File file = inConfigDirectory(configDirectory, fileName);
            if (file == null || !file.isFile()) {
                throw new FileNotFoundException("file not found: " + fileName);
            }
            return new FileInputStream(file);
        }
        URL url = ResourceUtils.class.getClassLoader().getResource(fileName);
        if (url == null) {
            throw new FileNotFoundException("file not found: " + fileName);
        }
        return ResourceUtils.class.getClassLoader().getResourceAsStream(fileName);
    }

    public static File[] getPathFiles(String path) throws FileNotFoundException {
        return getPathFiles(configDirectory(), path);
    }

    static File[] getPathFiles(Path configDirectory, String path) throws FileNotFoundException {
        String location = locate(configDirectory, path, "path not found: ");
        return Arrays.stream(Objects.requireNonNull(new File(location).listFiles(), "No files in " + path))
                     .filter(File::isFile).toArray(File[]::new);
    }

    /**
     * @param directoryPath the directory path
     * @param maxDepth      the max directory depth to get the files, the given directory is 0 as the tree root
     * @return all normal files which in this directory and subDirectory according to the maxDepth
     * @throws FileNotFoundException the directory not exist in the given path
     */
    public static List<File> getDirectoryFilesRecursive(String directoryPath,
                                                        int maxDepth) throws FileNotFoundException {
        return getDirectoryFilesRecursive(configDirectory(), directoryPath, maxDepth);
    }

    static List<File> getDirectoryFilesRecursive(Path configDirectory, String directoryPath,
                                                 int maxDepth) throws FileNotFoundException {
        String location = locate(configDirectory, directoryPath, "path not found: ");
        List<File> fileList = new ArrayList<>();
        return getDirectoryFilesRecursive(location, fileList, maxDepth);
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
        return getPath(configDirectory(), path);
    }

    static Path getPath(Path configDirectory, String path) {
        if (configDirectory != null) {
            File file = inConfigDirectory(configDirectory, path);
            return Objects.requireNonNull(file != null && file.exists() ? file : null, path).toPath();
        }
        return new File(Objects.requireNonNull(ResourceUtils.class.getClassLoader().getResource(path)).getPath()).toPath();
    }

    /**
     * @return the native image's {@code config/} directory, next to its executable; null on the JVM, which reads the
     * class path
     * @throws IllegalStateException in the native image, when there is no such directory
     */
    public static Path configDirectory() {
        String imageCode = System.getProperty(ImageInfo.PROPERTY_IMAGE_CODE_KEY);
        if (!ImageInfo.PROPERTY_IMAGE_CODE_VALUE_RUNTIME.equals(imageCode)) {
            return null;
        }
        return configDirectory(Path.of(ProcessProperties.getExecutableName()));
    }

    static Path configDirectory(Path executable) {
        Path directory = executable.toAbsolutePath().getParent().resolve(CONFIG_DIR);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException(
                "No " + directory + ": the native OAP reads its configuration from the " + CONFIG_DIR
                    + "/ directory next to its executable");
        }
        return directory;
    }

    private static String locate(Path configDirectory, String name, String notFound) throws FileNotFoundException {
        if (configDirectory != null) {
            File file = inConfigDirectory(configDirectory, name);
            if (file == null || !file.exists()) {
                throw new FileNotFoundException(notFound + name);
            }
            return file.getPath();
        }
        URL url = ResourceUtils.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new FileNotFoundException(notFound + name);
        }
        return url.getPath();
    }

    /**
     * @return the file {@code name} names under the directory, or null when it names one outside it, which a class
     * path lookup would not find either
     */
    private static File inConfigDirectory(Path configDirectory, String name) {
        Path resolved = configDirectory.resolve(name).normalize();
        return resolved.startsWith(configDirectory.normalize()) ? resolved.toFile() : null;
    }
}
