package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;

import java.util.List;

/**
 * Public config object accepted by ConfiguredGeneratorRuntimeManager.
 * Nested DTO records may contain optional/defaulted values; ConfigRegistrationLoader
 * converts them into strict core model records before runtime use.
 */
public record ConfigRuntimeConfig(IdDomainConfig idDomainInfo,
                                  List<GeneratorConfig> generators) {

    public record IdDomainConfig(Long domainId,
                                 String domainName,
                                 PrefixType prefixType,
                                 Integer timeBit,
                                 Integer workerBit,
                                 Integer sequenceBit,
                                 Long epochStartMs,
                                 TimeUnit timeUnit) {
    }

    public record GeneratorConfig(Long generatorId,
                                  String generatorName,
                                  GenerationPolicyConfig generationPolicy,
                                  Long workerId) {
    }

    public record GenerationPolicyConfig(Integer maximumRetry,
                                         IdRunOutPolicy idRunOutPolicy,
                                         ClockRollbackPolicy clockRollbackPolicy) {
    }
}
