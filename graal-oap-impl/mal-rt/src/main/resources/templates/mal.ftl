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
