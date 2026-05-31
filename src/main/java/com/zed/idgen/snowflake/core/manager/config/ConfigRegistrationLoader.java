package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.GenerationPolicyConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.IdDomainConfig;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registration loader for an immutable in-memory config object. All defaults,
 * uniqueness checks, and worker-id allocation are resolved once during
 * construction, so runtime lookups are simple map reads.
 */
public final class ConfigRegistrationLoader implements RegistrationLoader {
    private static final int DEFAULT_MAXIMUM_RETRY = 1;

    private final IdDomainInfo domainInfo;
    private final Map<Long, List<GeneratorProperties>> byGeneratorId;
    private final Map<String, Long> generatorNameToId;

    ConfigRegistrationLoader(ConfigRuntimeConfig config) {
        if (config == null) {
            throw new InvalidConfigException("config runtime config must not be null");
        }
        this.domainInfo = resolveDomainInfo(config.idDomainInfo());
        Map<Long, List<GeneratorProperties>> byId = new HashMap<>();
        Map<String, Long> byName = new HashMap<>();
        loadConfiguredGeneratorProperties(config.generators(), byId, byName);
        this.byGeneratorId = freezeByGeneratorId(byId);
        this.generatorNameToId = Map.copyOf(byName);
    }

    @Override
    public IdDomainInfo getDomainInfo() {
        return domainInfo;
    }

    @Override
    public long resolveGeneratorId(String generatorName) {
        String normalizedName = requireText(generatorName, "generator name");
        Long generatorId = generatorNameToId.get(normalizedName);
        if (generatorId == null) {
            throw new InvalidConfigException("generator config not found by name: " + normalizedName);
        }
        return generatorId;
    }

    @Override
    public List<GeneratorProperties> loadGeneratorProperties(long generatorId) {
        validateGeneratorId(generatorId);
        List<GeneratorProperties> properties = byGeneratorId.get(generatorId);
        if (properties == null || properties.isEmpty()) {
            throw new InvalidConfigException("generator config not found by id: " + generatorId);
        }
        return properties;
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

    private void loadConfiguredGeneratorProperties(List<ConfigRuntimeConfig.GeneratorConfig> configs,
                                                   Map<Long, List<GeneratorProperties>> byId,
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
            GeneratorPolicy generationPolicy = resolveGenerationPolicy(config.generationPolicy());
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
                    new GeneratorConfig(generatorId, generatorName, generationPolicy),
                    workerId,
                    null
            );
            addGeneratorProperty(byId, byName, generatorId, generatorName, generationPolicy, properties);
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
        validateGeneratorId(generatorId);
        return generatorId;
    }

    private static void validateGeneratorId(long generatorId) {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
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

    private static void addGeneratorProperty(Map<Long, List<GeneratorProperties>> byId,
                                             Map<String, Long> byName,
                                             long generatorId,
                                             String generatorName,
                                             GeneratorPolicy generationPolicy,
                                             GeneratorProperties properties) {
        List<GeneratorProperties> existingProperties = byId.get(generatorId);
        if (existingProperties != null && !existingProperties.isEmpty()) {
            GeneratorConfig existingConfig = existingProperties.getFirst().generatorConfig();
            if (!generatorName.equals(existingConfig.generatorName())) {
                throw new InvalidConfigException("same generator id has different names: " + generatorId);
            }
            if (!generationPolicy.equals(existingConfig.generationPolicy())) {
                throw new InvalidConfigException("same generator id has different generation policies: " + generatorId);
            }
        }

        Long existing = byName.putIfAbsent(generatorName, generatorId);
        if (existing != null && existing != generatorId) {
            throw new InvalidConfigException("duplicated generator name: " + generatorName);
        }

        byId.computeIfAbsent(generatorId, ignored -> new ArrayList<>()).add(properties);
    }

    private static Map<Long, List<GeneratorProperties>> freezeByGeneratorId(Map<Long, List<GeneratorProperties>> byId) {
        Map<Long, List<GeneratorProperties>> frozen = new HashMap<>();
        for (Map.Entry<Long, List<GeneratorProperties>> entry : byId.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }
}
