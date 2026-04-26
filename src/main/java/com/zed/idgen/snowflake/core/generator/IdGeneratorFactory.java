package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.component.provider.impl.DefaultIdProvider;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.model.GeneratorInfo;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.List;

public final class IdGeneratorFactory {
    private IdGeneratorFactory() {
    }

    public static IdGenerator create(GeneratorInstanceProperties properties) {
        if (properties == null) {
            throw new InvalidConfigException("generator instance properties must not be null");
        }
        IdDomainInfo domainInfo = requireDomainInfo(properties);
        GeneratorInfo generatorInfo = requireGeneratorInfo(properties);
        validatePolicy(generatorInfo.generationPolicy());

        IdCodec idCodec = new IdCodec(domainInfo);
        long worker = resolveSingleWorker(properties.workers(), idCodec);

        return new DefaultIdGenerator(new DefaultIdProvider(worker, idCodec, generatorInfo));
    }

    private static IdDomainInfo requireDomainInfo(GeneratorInstanceProperties properties) {
        if (properties.idDomainInfo() == null) {
            throw new InvalidConfigException("id domain info must not be null");
        }
        return properties.idDomainInfo();
    }

    private static GeneratorInfo requireGeneratorInfo(GeneratorInstanceProperties properties) {
        if (properties.generatorInfo() == null) {
            throw new InvalidConfigException("generator info must not be null");
        }
        if (properties.generatorInfo().generatorName() == null || properties.generatorInfo().generatorName().isBlank()) {
            throw new InvalidConfigException("generator name must not be blank");
        }
        return properties.generatorInfo();
    }

    private static void validatePolicy(GeneratorPolicy policy) {
        if (policy == null) {
            throw new InvalidConfigException("generation policy must not be null");
        }
        if (policy.maximumRetry() < 0) {
            throw new InvalidConfigException("maximum retry must not be negative");
        }
        if (policy.idRunOutPolicy() == null) {
            throw new InvalidConfigException("id run out policy must not be null");
        }
        if (policy.clockRollbackPolicy() == null) {
            throw new InvalidConfigException("clock rollback policy must not be null");
        }
    }

    private static long resolveSingleWorker(List<Long> workers, IdCodec idCodec) {
        if (workers == null || workers.size() != 1 || workers.getFirst() == null) {
            throw new InvalidConfigException("exactly one worker is required");
        }
        long worker = workers.getFirst();
        if (worker < 0 || worker > idCodec.getMaxWorker()) {
            throw new InvalidConfigException("worker id exceeds domain worker range: " + worker);
        }
        return worker;
    }
}
