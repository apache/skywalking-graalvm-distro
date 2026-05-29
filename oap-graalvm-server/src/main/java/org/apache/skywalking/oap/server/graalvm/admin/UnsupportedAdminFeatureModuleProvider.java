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

package org.apache.skywalking.oap.server.graalvm.admin;

import com.linecorp.armeria.common.HttpMethod;
import java.util.Arrays;
import org.apache.skywalking.oap.server.admin.server.module.AdminServerModule;
import org.apache.skywalking.oap.server.core.server.HTTPHandlerRegister;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleDefine;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.ServiceNotProvidedException;

/**
 * Registers {@link UnsupportedAdminFeatureHandler} on the admin-server HTTP host. The
 * module carries no configuration ({@link #newConfigCreator()} returns null), so it needs
 * no {@code application.yml} section and no config-generator entry — it is wired directly
 * by {@code GraalVMOAPServerStartUp}.
 */
public class UnsupportedAdminFeatureModuleProvider extends ModuleProvider {

    @Override
    public String name() {
        return "default";
    }

    @Override
    public Class<? extends ModuleDefine> module() {
        return UnsupportedAdminFeatureModule.class;
    }

    @Override
    public ConfigCreator<? extends ModuleConfig> newConfigCreator() {
        return null;
    }

    @Override
    public void prepare() throws ServiceNotProvidedException, ModuleStartException {
    }

    @Override
    public void start() throws ServiceNotProvidedException, ModuleStartException {
        final HTTPHandlerRegister adminRegister = getManager().find(AdminServerModule.NAME)
                                                              .provider()
                                                              .getService(HTTPHandlerRegister.class);
        adminRegister.addHandler(
            new UnsupportedAdminFeatureHandler(),
            Arrays.asList(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)
        );
    }

    @Override
    public void notifyAfterCompleted() throws ServiceNotProvidedException, ModuleStartException {
    }

    @Override
    public String[] requiredModules() {
        return new String[] {
            AdminServerModule.NAME,
        };
    }
}
