package com.zed.idgen.snowflake.core.constant;

public record GeneratorProperties(
        GeneratorInstanceProperties generatorInstanceProperties,
        String az,
        String instanceId
) {}
