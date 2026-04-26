package com.zed.idgen.snowflake.core.model;

public record GeneratorInfo(
        long generatorId,
        String generatorName,
        Long domainId,
        String domainName,
        GeneratorPolicy generationPolicy
) {}
