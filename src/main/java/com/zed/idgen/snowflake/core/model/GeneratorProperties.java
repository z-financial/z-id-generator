package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

import java.util.List;

public record GeneratorProperties(
        IdDomainInfo idDomainInfo,
        GeneratorConfig generatorConfig,
        List<Long> workerIds,
        GeneratorMetadata metadata) {

    public GeneratorProperties {
        if (idDomainInfo == null) {
            throw new InvalidConfigException("id domain info must not be null");
        }
        if (generatorConfig == null) {
            throw new InvalidConfigException("generator config must not be null");
        }
        if (workerIds == null) {
            throw new InvalidConfigException("worker ids must not be null");
        }
        workerIds = List.copyOf(workerIds);
    }
}
