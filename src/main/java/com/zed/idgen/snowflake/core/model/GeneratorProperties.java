package com.zed.idgen.snowflake.core.model;

public record GeneratorProperties(
        GeneratorInstanceProperties generatorInstanceProperties,
        String az,
        String instanceId
) {}
