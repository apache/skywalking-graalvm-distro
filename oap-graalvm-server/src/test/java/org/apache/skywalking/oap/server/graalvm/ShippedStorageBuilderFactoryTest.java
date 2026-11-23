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

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.NewExpr;
import org.apache.skywalking.oap.server.buildtools.common.AcceptedModules;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;
import org.apache.skywalking.oap.server.core.storage.StorageModule;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The precompiler generates the OAL storage builders with {@link StorageBuilderFactory.Default}, where upstream takes
 * the factory the storage provider registers: the shipped storage provider must register that one.
 */
class ShippedStorageBuilderFactoryTest {

    @Test
    void theShippedStorageRegistersTheBuilderFactoryThePrecompilerGeneratesOalBuildersWith() throws Exception {
        final List<String> storages = AcceptedModules.ACCEPTED.stream()
                                                              .filter(p -> StorageModule.NAME.equals(p.moduleName()))
                                                              .map(AcceptedModules.ModuleProviderPair::providerName)
                                                              .collect(Collectors.toList());
        assertEquals(List.of(new BanyanDBStorageProvider().name()), storages);
        assertEquals(Set.of(StorageBuilderFactory.Default.class.getName()),
            builderFactoriesCreatedBy(BanyanDBStorageProvider.class));
    }

    private static Set<String> builderFactoriesCreatedBy(final Class<?> provider)
        throws NotFoundException, CannotCompileException {
        final ClassPool pool = new ClassPool(true);
        final CtClass factory = pool.get(StorageBuilderFactory.class.getName());
        final Set<String> created = new TreeSet<>();
        pool.get(provider.getName()).instrument(new ExprEditor() {
            @Override
            public void edit(final NewExpr expr) throws CannotCompileException {
                try {
                    if (pool.get(expr.getClassName()).subtypeOf(factory)) {
                        created.add(expr.getClassName());
                    }
                } catch (NotFoundException e) {
                    throw new CannotCompileException(e);
                }
            }
        });
        return created;
    }
}
