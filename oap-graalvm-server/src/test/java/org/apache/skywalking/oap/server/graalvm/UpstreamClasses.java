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

package org.apache.skywalking.oap.server.graalvm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Upstream's classes of the replacements' names, loaded from upstream's jars on the test class path (the replacements
 * shadow them), before the parent, with the classes of those jars they use: what upstream does, side by side with what
 * the distro does.
 */
public final class UpstreamClasses extends URLClassLoader {
    public UpstreamClasses(final Class<?>... replaced) throws Exception {
        super(jars(replaced), UpstreamClasses.class.getClassLoader());
    }

    private static URL[] jars(final Class<?>... replaced) throws Exception {
        final List<URL> jars = new ArrayList<>();
        for (final Class<?> type : replaced) {
            final String resource = type.getName().replace('.', '/') + ".class";
            URL jar = null;
            for (final URL url : Collections.list(UpstreamClasses.class.getClassLoader().getResources(resource))) {
                if ("jar".equals(url.getProtocol()) && !url.getPath().contains("-for-graalvm")) {
                    jar = new URL(url.getPath().substring(0, url.getPath().indexOf("!/")));
                }
            }
            assertNotNull(jar, "upstream's " + type.getName() + " is not on the test class path");
            jars.add(jar);
        }
        return jars.toArray(new URL[0]);
    }

    /**
     * @return upstream's class of the replacement's name
     */
    public Class<?> of(final Class<?> replacement) throws ClassNotFoundException {
        return loadClass(replacement.getName());
    }

    /**
     * @return what a static method of an upstream class returns, its own exception thrown as it is
     */
    public static Object invoke(final Method method, final Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (final InvocationTargetException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : new Exception(e.getCause());
        }
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (final ClassNotFoundException notInTheJars) {
                    return super.loadClass(name, resolve);
                }
            }
            return loaded;
        }
    }
}
