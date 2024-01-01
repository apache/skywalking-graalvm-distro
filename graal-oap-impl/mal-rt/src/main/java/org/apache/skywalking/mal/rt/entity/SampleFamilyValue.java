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

package org.apache.skywalking.mal.rt.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;

@AllArgsConstructor
@Getter
public class SampleFamilyValue implements Value {
    private SampleFamily value;
    public static SampleFamilyValue as(SampleFamily sampleFamily) {
        return new SampleFamilyValue(sampleFamily);
    }

    @Override
    public SampleFamily getValueAsSampleFamily() {
        return this.getValue();
    }

    @Override
    public Number getValueAsScalar() {
        throw new UnsupportedOperationException("SampleFamily can not cast to Number");
    }

    @Override
    public boolean sampleFamily() {
        return true;
    }

    @Override
    public Value minus(Value another) {
        if(another.sampleFamily()) {
            return SampleFamilyValue.as(this.getValue().minus(another.getValueAsSampleFamily()));
        }
        return SampleFamilyValue.as(this.getValue().minus(another.getValueAsScalar()));
    }

    @Override
    public Value plus(Value another) {
        if(another.sampleFamily()) {
            return SampleFamilyValue.as(this.getValue().plus(another.getValueAsSampleFamily()));
        }
        return SampleFamilyValue.as(this.getValue().plus(another.getValueAsScalar()));
    }

    @Override
    public Value multiply(Value another) {
        if(another.sampleFamily()) {
            return SampleFamilyValue.as(this.getValue().multiply(another.getValueAsSampleFamily()));
        }
        return SampleFamilyValue.as(this.getValue().multiply(another.getValueAsScalar()));
    }

    @Override
    public Value div(Value another) {
        if(another.sampleFamily()) {
            return SampleFamilyValue.as(this.getValue().div(another.getValueAsSampleFamily()));
        }
        return SampleFamilyValue.as(this.getValue().div(another.getValueAsScalar()));
    }
}

