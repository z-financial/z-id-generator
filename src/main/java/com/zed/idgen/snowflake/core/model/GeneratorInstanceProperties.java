package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

import java.util.List;

public record GeneratorInstanceProperties(
        IdDomainInfo idDomainInfo,
        List<Long> workers,
        GeneratorPolicy generationPolicy,
        long generatorId,
        String generatorName) {

    public GeneratorInstanceProperties {
        if (idDomainInfo == null) {
            throw new InvalidConfigException("id domain info must not be null");
        }
        if (workers != null) {
            workers = List.copyOf(workers);
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
                properties.workerIds(),
                config.generationPolicy(),
                config.generatorId(),
                config.generatorName());
    }
}
