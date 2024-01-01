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


import lombok.Getter;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;

@Getter
public class ScalarValue implements Value {
    private Number value;

    public ScalarValue(Number number) {
        this.value = number;
    }

    public static ScalarValue as(Number number) {
        return new ScalarValue(number);
    }

    @Override
    public SampleFamily getValueAsSampleFamily() {
        throw new UnsupportedOperationException("Number can not cast to SampleFamily");
    }

    @Override
    public Number getValueAsScalar() {
        return value;
    }

    @Override
    public boolean sampleFamily() {
        return false;
    }

    @Override
    public Value minus(Value another) {
        if (another.sampleFamily()) {
            throw new UnsupportedOperationException("Scalar can not minus SampleFamily");
        } else {
            return ScalarValue.as(this.getValue().doubleValue() - another.getValueAsScalar().doubleValue());
        }
    }

    @Override
    public Value plus(Value another) {
        if (another.sampleFamily()) {
            return SampleFamilyValue.as(another.getValueAsSampleFamily().plus(this.getValue()));
        } else {
            return ScalarValue.as(this.getValue().doubleValue() + another.getValueAsScalar().doubleValue());
        }
    }

    @Override
    public Value multiply(Value another) {
        if (another.sampleFamily()) {
            return SampleFamilyValue.as(another.getValueAsSampleFamily().multiply(this.getValue()));
        } else {
            return ScalarValue.as(this.getValue().doubleValue() * another.getValueAsScalar().doubleValue());
        }
    }

    @Override
    public Value div(Value another) {
        if (another.sampleFamily()) {
            throw new UnsupportedOperationException("Scalar can not div SampleFamily");
        } else {
            return ScalarValue.as(this.getValue().doubleValue() / another.getValueAsScalar().doubleValue());
        }
    }

}