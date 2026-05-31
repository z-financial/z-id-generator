package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * One registered worker binding under one generator config.
 * A generator config may own many workers, but each GeneratorProperties
 * instance represents exactly one worker that can become one runtime generator.
 */
public record GeneratorProperties(
        IdDomainInfo idDomainInfo,
        GeneratorConfig generatorConfig,
        long workerId,
        GeneratorMetadata metadata) {

    public GeneratorProperties {
        if (idDomainInfo == null) {
            throw new InvalidConfigException("id domain info must not be null");
        }
        if (generatorConfig == null) {
            throw new InvalidConfigException("generator config must not be null");
        }
        if (workerId < 0) {
            throw new InvalidConfigException("worker id must not be negative");
        }
        long maxWorker = new IdCodec(idDomainInfo).getMaxWorker();
        if (workerId > maxWorker) {
            throw new InvalidConfigException("worker id exceeds domain worker range: " + workerId);
        }
    }
}
