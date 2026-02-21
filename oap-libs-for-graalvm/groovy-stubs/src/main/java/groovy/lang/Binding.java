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

package groovy.lang;

import java.util.LinkedHashMap;
import java.util.Map;

public class Binding extends GroovyObjectSupport {
    private Map<String, Object> variables;

    public Binding() {
        this.variables = new LinkedHashMap<>();
    }

    public Binding(Map<String, Object> variables) {
        this.variables = variables;
    }

    public Object getVariable(String name) {
        Object result = variables.get(name);
        if (result == null && !variables.containsKey(name)) {
            throw new MissingPropertyException(name, getClass());
        }
        return result;
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    @Override
    public Object getProperty(String propertyName) {
        return getVariable(propertyName);
    }

    @Override
    public void setProperty(String propertyName, Object newValue) {
        setVariable(propertyName, newValue);
    }
}
