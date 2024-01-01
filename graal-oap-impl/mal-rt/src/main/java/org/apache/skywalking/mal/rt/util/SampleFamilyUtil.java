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

package org.apache.skywalking.mal.rt.util;

import groovy.lang.Closure;

import org.apache.skywalking.mal.rt.entity.GraalClosure;
import org.apache.skywalking.oap.meter.analyzer.dsl.DownsamplingType;

import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;
import org.apache.skywalking.oap.server.core.analysis.Layer;

import org.apache.skywalking.oap.server.core.source.DetectPoint;

import java.util.List;
import java.util.Map;

import java.util.concurrent.TimeUnit;


public class SampleFamilyUtil {

    public static SampleFamily tag(SampleFamily sampleFamily, GraalClosure graalClosure) {
        //todo: add actual impl
        return sampleFamily;
    }

    public static SampleFamily foreach(SampleFamily sampleFamily, GraalClosure graalClosure) {
        //todo: add actual impl
        return sampleFamily;
    }


    public static SampleFamily tagEqual(SampleFamily sampleFamily, String[] labels) {
        return sampleFamily.tagEqual(labels);
    }

    public static SampleFamily tagNotEqual(SampleFamily sampleFamily, String[] labels) {
        return sampleFamily.tagNotEqual(labels);
    }

    public static SampleFamily tagMatch(SampleFamily sampleFamily, String[] labels) {
        return sampleFamily.tagMatch(labels);
    }

    public static SampleFamily tagNotMatch(SampleFamily sampleFamily, String[] labels) {
        return sampleFamily.tagNotMatch(labels);
    }

    public static SampleFamily valueEqual(SampleFamily sampleFamily, double compValue) {
        return sampleFamily.valueEqual(compValue);
    }

    public static SampleFamily valueNotEqual(SampleFamily sampleFamily, double compValue) {
        return sampleFamily.valueNotEqual(compValue);
    }

    public static SampleFamily valueGreaterEqual(SampleFamily sampleFamily, double compValue) {
        return sampleFamily.valueGreaterEqual(compValue);
    }

    public static SampleFamily valueGreater(SampleFamily sampleFamily, double compValue) {
        return sampleFamily.valueGreater(compValue);
    }

    public static SampleFamily valueLess(SampleFamily sampleFamily, double compValue) {
        return sampleFamily.valueLess(compValue);
    }

    public static SampleFamily valueLessEqual(SampleFamily sampleFamily, double compValue) {
        return sampleFamily.valueLessEqual(compValue);
    }

    /* Aggregation operators */
    public static SampleFamily sum(SampleFamily sampleFamily, List<String> by) {
        return sampleFamily.sum(by);
    }

    public static SampleFamily max(SampleFamily sampleFamily, List<String> by) {
        return sampleFamily.max(by);
    }

    public static SampleFamily min(SampleFamily sampleFamily,  List<String> by) {
        return sampleFamily.min(by);
    }

    public static SampleFamily avg(SampleFamily sampleFamily,  List<String> by) {
        return sampleFamily.avg(by);
    }

    /* Function */
    public static SampleFamily increase(SampleFamily sampleFamily, String range) {
        return sampleFamily.increase(range);
    }

    public static SampleFamily rate(SampleFamily sampleFamily, String range) {
        return sampleFamily.rate(range);
    }

    public static SampleFamily irate(SampleFamily sampleFamily) {
        return sampleFamily.irate();
    }

    /* k8s retags*/
    public static SampleFamily retagByK8sMeta(SampleFamily sampleFamily,
                                       String newLabelName,
                                       K8sRetagType type,
                                       String existingLabelName,
                                       String namespaceLabelName) {
        return sampleFamily.retagByK8sMeta(newLabelName, type, existingLabelName, namespaceLabelName);
    }

    public static SampleFamily histogram(SampleFamily sampleFamily) {
        return sampleFamily.histogram();
    }

    public static SampleFamily histogram(SampleFamily sampleFamily, String le) {
        return sampleFamily.histogram(le);
    }

    public static SampleFamily histogram(SampleFamily sampleFamily, String le, TimeUnit unit) {
        return sampleFamily.histogram(le, unit);
    }

    public static SampleFamily histogram_percentile(SampleFamily sampleFamily, List<Integer> percentiles) {
        return sampleFamily.histogram_percentile(percentiles);
    }

    public static SampleFamily service(SampleFamily sampleFamily, List<String> labelKeys, Layer layer) {
        return sampleFamily.service(labelKeys, layer);
    }

    public static SampleFamily service(SampleFamily sampleFamily, List<String> labelKeys, String delimiter, Layer layer) {
        return sampleFamily.service(labelKeys, delimiter, layer);
    }

    public static SampleFamily instance(SampleFamily sampleFamily, List<String> serviceKeys, String serviceDelimiter,
                                 List<String> instanceKeys, String instanceDelimiter,
                                 Layer layer, Closure<Map<String, String>> propertiesExtractor) {
        return sampleFamily.instance(serviceKeys, serviceDelimiter, instanceKeys, instanceDelimiter, layer, propertiesExtractor);
    }

    public static SampleFamily instance(SampleFamily sampleFamily, List<String> serviceKeys, List<String> instanceKeys, Layer layer) {
        return sampleFamily.instance(serviceKeys, instanceKeys, layer);
    }

    public static SampleFamily endpoint(SampleFamily sampleFamily, List<String> serviceKeys, List<String> endpointKeys, String delimiter, Layer layer) {
        return sampleFamily.endpoint(serviceKeys, endpointKeys, delimiter, layer);
    }

    public static SampleFamily endpoint(SampleFamily sampleFamily, List<String> serviceKeys, List<String> endpointKeys, Layer layer) {
        return sampleFamily.endpoint(serviceKeys, endpointKeys, layer);
    }

    public static SampleFamily process(SampleFamily sampleFamily, List<String> serviceKeys, List<String> serviceInstanceKeys, List<String> processKeys, String layerKey) {
       return sampleFamily.process(serviceKeys, serviceInstanceKeys, processKeys, layerKey);
    }

    public static SampleFamily serviceRelation(SampleFamily sampleFamily, DetectPoint detectPoint,List<String> sourceServiceKeys, List<String> destServiceKeys, Layer layer) {
        return sampleFamily.serviceRelation(detectPoint, sourceServiceKeys, destServiceKeys, layer);
    }

    public static SampleFamily serviceRelation(SampleFamily sampleFamily, DetectPoint detectPoint, List<String> sourceServiceKeys, List<String> destServiceKeys, String delimiter, Layer layer, String componentIdKey) {
        return sampleFamily.serviceRelation(detectPoint, sourceServiceKeys, destServiceKeys, delimiter, layer, componentIdKey);
    }

    public static SampleFamily processRelation(SampleFamily sampleFamily, String detectPointKey, List<String> serviceKeys, List<String> instanceKeys, String sourceProcessIdKey, String destProcessIdKey, String componentKey) {
        return sampleFamily.processRelation(detectPointKey, serviceKeys, instanceKeys, sourceProcessIdKey, destProcessIdKey, componentKey);
    }

    public static SampleFamily downsampling(SampleFamily sampleFamily, final DownsamplingType type) {
        return sampleFamily.downsampling(type);
    }

}
