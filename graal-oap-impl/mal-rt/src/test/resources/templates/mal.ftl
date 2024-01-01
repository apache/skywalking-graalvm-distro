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
 *
 */

package org.apache.skywalking.mal.rt.generated;
<#list imports as import>
import ${import};
</#list>

public class ${className} {

    private LinkedList<Value> stack = new LinkedList<>();
    private Map<String, SampleFamily> sampleFamilyMap;

    public ${className} (Map<String, SampleFamily> map) {
        this.sampleFamilyMap = map;
    }

    public void execute() {
        ${executeBody}
    }

}
