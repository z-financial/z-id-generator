package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Runtime input for building one local IdGenerator instance.
 * Each instance owns exactly one worker id; multi-worker generator configs are
 * represented as multiple GeneratorProperties before this runtime boundary.
 */
public record GeneratorInstanceProperties(
        IdDomainInfo idDomainInfo,
        long workerId,
        GeneratorPolicy generationPolicy,
        long generatorId,
        String generatorName) {

    public GeneratorInstanceProperties {
        if (idDomainInfo == null) {
            throw new InvalidConfigException("id domain info must not be null");
        }
        if (workerId < 0) {
            throw new InvalidConfigException("worker id must not be negative");
        }
        if (generationPolicy == null) {
            throw new InvalidConfigException("generation policy must not be null");
        }
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
        if (generatorName == null || generatorName.isBlank()) {
            throw new InvalidConfigException("generator name must not be blank");
        }
    }

    public static GeneratorInstanceProperties from(GeneratorProperties properties) {
        if (properties == null) {
            throw new InvalidConfigException("generator properties must not be null");
        }
        GeneratorConfig config = properties.generatorConfig();
        return new GeneratorInstanceProperties(
                properties.idDomainInfo(),
                properties.workerId(),
                config.generationPolicy(),
                config.generatorId(),
                config.generatorName());
    }
}
