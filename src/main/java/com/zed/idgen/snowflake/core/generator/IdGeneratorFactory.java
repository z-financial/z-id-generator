package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.component.provider.impl.DefaultIdProvider;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
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
        validateGeneratorIdentity(properties);
        validatePolicy(properties.generationPolicy());

        IdCodec idCodec = new IdCodec(domainInfo);
        long worker = resolveSingleWorker(properties.workers(), idCodec);

        return new DefaultIdGenerator(new DefaultIdProvider(worker, idCodec, properties.generationPolicy()));
    }

    private static IdDomainInfo requireDomainInfo(GeneratorInstanceProperties properties) {
        if (properties.idDomainInfo() == null) {
            throw new InvalidConfigException("id domain info must not be null");
        }
        return properties.idDomainInfo();
    }

    private static void validateGeneratorIdentity(GeneratorInstanceProperties properties) {
        if (properties.generatorId() < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
        if (properties.generatorName() == null || properties.generatorName().isBlank()) {
            throw new InvalidConfigException("generator name must not be blank");
        }
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
        // receiving a list of works for forward compatibility. now only allow single worker
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
