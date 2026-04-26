package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

public record GeneratorConfig(
        long generatorId,
        String generatorName,
        GeneratorPolicy generationPolicy) {

    public GeneratorConfig {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
        if (generatorName == null || generatorName.isBlank()) {
            throw new InvalidConfigException("generator name must not be blank");
        }
        if (generationPolicy == null) {
            throw new InvalidConfigException("generation policy must not be null");
        }
    }
}
