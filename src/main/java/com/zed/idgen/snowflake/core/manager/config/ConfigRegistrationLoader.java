package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.GenerationPolicyConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.IdDomainConfig;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registration loader for an immutable in-memory config object. All defaults,
 * uniqueness checks, and worker-id allocation are resolved once during
 * construction so runtime lookups are simple map reads.
 */
public final class ConfigRegistrationLoader implements RegistrationLoader {
    private static final int DEFAULT_MAXIMUM_RETRY = 1;

    private final IdDomainInfo domainInfo;
    private final Map<Long, GeneratorProperties> byGeneratorId;
    private final Map<String, Long> generatorNameToId;

    ConfigRegistrationLoader(ConfigRuntimeConfig config) {
        if (config == null) {
            throw new InvalidConfigException("config runtime config must not be null");
        }
        this.domainInfo = resolveDomainInfo(config.idDomainInfo());
        Map<Long, GeneratorProperties> byId = new HashMap<>();
        Map<String, Long> byName = new HashMap<>();
        loadGeneratorProperties(config.generators(), byId, byName);
        this.byGeneratorId = Map.copyOf(byId);
        this.generatorNameToId = Map.copyOf(byName);
    }

    @Override
    public IdDomainInfo loadDomain() {
        return domainInfo;
    }

    @Override
    public GeneratorProperties loadGenerator(GeneratorParam generatorParam) {
        if (generatorParam == null) {
            throw new InvalidConfigException("generator param must not be null");
        }

        Long generatorId = generatorParam.generatorId();
        String generatorName = generatorParam.generatorName();
        if (generatorId != null) {
            GeneratorProperties properties = byGeneratorId.get(generatorId);
            if (properties == null) {
                throw new InvalidConfigException("generator config not found by id: " + generatorId);
            }
            if (generatorName != null && !generatorName.equals(properties.generatorConfig().generatorName())) {
                throw new InvalidConfigException("generator name mismatch, requested: " + generatorName
                        + ", config: " + properties.generatorConfig().generatorName());
            }
            return properties;
        }

        Long indexedGeneratorId = generatorNameToId.get(generatorName);
        if (indexedGeneratorId == null) {
            throw new InvalidConfigException("generator config not found by name: " + generatorName);
        }
        return byGeneratorId.get(indexedGeneratorId);
    }

    private static IdDomainInfo resolveDomainInfo(IdDomainConfig config) {
        if (config == null) {
            throw new InvalidConfigException("id domain info config must not be null");
        }
        String domainName = requireText(config.domainName(), "domain name");
        return new IdDomainInfo(
                config.domainId(),
                domainName,
                config.prefixType() == null ? PrefixType.POSITIVE_SIGNAL : config.prefixType(),
                config.timeBit(),
                config.workerBit(),
                config.sequenceBit(),
                config.epochStartMs(),
                config.timeUnit() == null ? TimeUnit.MILLISECOND : config.timeUnit()
        );
    }

    private void loadGeneratorProperties(List<ConfigRuntimeConfig.GeneratorConfig> configs,
                                         Map<Long, GeneratorProperties> byId,
                                         Map<String, Long> byName) {
        if (configs == null || configs.isEmpty()) {
            throw new InvalidConfigException("generator config list must not be empty");
        }

        IdCodec idCodec = new IdCodec(domainInfo);
        long maxWorker = idCodec.getMaxWorker();
        Set<Long> usedWorkerIds = collectExplicitWorkerIds(configs, maxWorker);
        long nextWorkerId = 0L;

        for (ConfigRuntimeConfig.GeneratorConfig config : configs) {
            if (config == null) {
                throw new InvalidConfigException("generator config item must not be null");
            }

            long generatorId = requireGeneratorId(config.generatorId());
            String generatorName = requireText(config.generatorName(), "generator name");
            long workerId;
            if (config.workerId() == null) {
                workerId = allocateWorkerId(usedWorkerIds, nextWorkerId, maxWorker);
                usedWorkerIds.add(workerId);
                nextWorkerId = workerId + 1;
            } else {
                workerId = config.workerId();
            }

            GeneratorProperties properties = new GeneratorProperties(
                    domainInfo,
                    new GeneratorConfig(generatorId, generatorName, resolveGenerationPolicy(config.generationPolicy())),
                    List.of(workerId),
                    null
            );
            putUniqueById(byId, generatorId, properties);
            putUniqueByName(byName, generatorName, generatorId);
        }
    }

    private static Set<Long> collectExplicitWorkerIds(List<ConfigRuntimeConfig.GeneratorConfig> configs, long maxWorker) {
        Set<Long> usedWorkerIds = new HashSet<>();
        for (ConfigRuntimeConfig.GeneratorConfig config : configs) {
            if (config == null || config.workerId() == null) {
                continue;
            }
            long workerId = config.workerId();
            validateWorkerId(workerId, maxWorker);
            if (!usedWorkerIds.add(workerId)) {
                throw new InvalidConfigException("duplicated worker id: " + workerId);
            }
        }
        return usedWorkerIds;
    }

    private static long allocateWorkerId(Set<Long> usedWorkerIds, long start, long maxWorker) {
        long candidate = start;
        while (candidate <= maxWorker) {
            if (!usedWorkerIds.contains(candidate)) {
                return candidate;
            }
            candidate++;
        }
        throw new InvalidConfigException("no available worker id under max worker id: " + maxWorker);
    }

    private static GeneratorPolicy resolveGenerationPolicy(GenerationPolicyConfig config) {
        if (config == null) {
            return new GeneratorPolicy(DEFAULT_MAXIMUM_RETRY, IdRunOutPolicy.NONE, ClockRollbackPolicy.NONE);
        }
        return new GeneratorPolicy(
                config.maximumRetry() == null ? DEFAULT_MAXIMUM_RETRY : config.maximumRetry(),
                config.idRunOutPolicy() == null ? IdRunOutPolicy.NONE : config.idRunOutPolicy(),
                config.clockRollbackPolicy() == null ? ClockRollbackPolicy.NONE : config.clockRollbackPolicy()
        );
    }

    private static long requireGeneratorId(Long generatorId) {
        if (generatorId == null) {
            throw new InvalidConfigException("generator id must not be null");
        }
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
        return generatorId;
    }

    private static void validateWorkerId(long workerId, long maxWorker) {
        if (workerId < 0) {
            throw new InvalidConfigException("worker id must not be negative");
        }
        if (workerId > maxWorker) {
            throw new InvalidConfigException("worker id exceeds domain worker range: " + workerId);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static void putUniqueById(Map<Long, GeneratorProperties> byId,
                                      long generatorId,
                                      GeneratorProperties properties) {
        GeneratorProperties existing = byId.putIfAbsent(generatorId, properties);
        if (existing != null) {
            throw new InvalidConfigException("duplicated generator id: " + generatorId);
        }
    }

    private static void putUniqueByName(Map<String, Long> byName,
                                        String generatorName,
                                        long generatorId) {
        Long existing = byName.putIfAbsent(generatorName, generatorId);
        if (existing != null) {
            throw new InvalidConfigException("duplicated generator name: " + generatorName);
        }
    }
}
