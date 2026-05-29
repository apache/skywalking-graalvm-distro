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

import org.apache.skywalking.oap.server.library.module.ModuleDefine;

/**
 * GraalVM-distro-only module that serves a friendly "not available" response for the
 * admin features this distro cannot support in a native image: runtime-rule hot-update
 * and the DSL live debugger. Both rely on runtime Javassist class generation, which is
 * impossible under the closed-world assumption of a native image.
 *
 * <p>Without this module the Horizon UI would hit connection-refused / 404 on the
 * {@code /dsl-debugging/*} and {@code /runtime/*} endpoints. The stub mounts those routes
 * on the admin-server HTTP host and returns 501 with an explanatory payload instead.
 */
public class UnsupportedAdminFeatureModule extends ModuleDefine {
    public static final String NAME = "graalvm-unsupported-admin";

    public UnsupportedAdminFeatureModule() {
        super(NAME);
    }

    @Override
    public Class[] services() {
        return new Class[] {};
    }
}
