package org.apache.skywalking.mal.rt.generated;
<#list imports as import>
import ${import};
</#list>

public class ${className} implements org.apache.skywalking.mal.rt.entity.GraalClosure{

    @Override
    public Object execute(Map<String, Object> variables) {
        ${executeBody}
    }

}
