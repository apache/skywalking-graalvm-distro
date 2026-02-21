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

public abstract class Script extends GroovyObjectSupport {
    private Binding binding;

    protected Script() {
        this.binding = new Binding();
    }

    protected Script(Binding binding) {
        this.binding = binding;
    }

    public Binding getBinding() {
        return binding;
    }

    public void setBinding(Binding binding) {
        this.binding = binding;
    }

    public Object getProperty(String property) {
        try {
            return binding.getVariable(property);
        } catch (MissingPropertyException e) {
            return super.getProperty(property);
        }
    }

    public void setProperty(String property, Object newValue) {
        binding.setVariable(property, newValue);
    }

    public abstract Object run();
}
