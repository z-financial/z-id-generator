package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoSession;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.WorkerEntity;
import com.zed.idgen.snowflake.core.manager.mysql.enums.DomainPrefixType;
import com.zed.idgen.snowflake.core.manager.mysql.enums.DomainTimeUnit;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RegistrationLoader backed by MySQL registration tables.
 * <p>
 * Domain information is lazily loaded and cached as a volatile immutable
 * snapshot. The volatile write safely publishes the loaded IdDomainInfo to
 * other threads. We intentionally do not synchronize initialization: duplicate
 * DB loads are acceptable because domain rows are treated as immutable and the
 * conversion from DB row to IdDomainInfo is deterministic.
 */
public final class MysqlRegistrationLoader implements RegistrationLoader {
    private static final int DEFAULT_MAXIMUM_RETRY = 1;
    private static final Map<String, IdRunOutPolicy> ID_RUN_OUT_POLICIES = Map.of(
            "NONE", IdRunOutPolicy.NONE
    );
    private static final Map<String, ClockRollbackPolicy> CLOCK_ROLLBACK_POLICIES = Map.of(
            "NONE", ClockRollbackPolicy.NONE
    );
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    private final MysqlDaoFactory daoFactory;
    private final String domainName;
    private volatile IdDomainInfo domainInfo;

    public MysqlRegistrationLoader(MysqlDaoFactory daoFactory, String domainName) {
        if (daoFactory == null) {
            throw new InvalidConfigException("mysql dao factory must not be null");
        }
        this.daoFactory = daoFactory;
        this.domainName = requireText(domainName, "domain name");
    }

    @Override
    public IdDomainInfo getDomainInfo() {
        IdDomainInfo current = domainInfo;
        if (current != null) {
            return current;
        }

        IdDomainInfo loaded = loadDomainInfo();
        domainInfo = loaded;
        return loaded;
    }

    @Override
    public long resolveGeneratorId(String generatorName) {
        String normalizedName = requireText(generatorName, "generator name");
        long domainId = getDomainInfo().domainId();
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            Long generatorId = session.generatorConfigDao().findIdByName(domainId, normalizedName);
            if (generatorId == null) {
                throw new InvalidConfigException("generator config not found by name: " + normalizedName);
            }
            return generatorId;
        }
    }

    @Override
    public List<GeneratorProperties> loadGeneratorProperties(long generatorId) {
        validateGeneratorId(generatorId);
        IdDomainInfo domain = getDomainInfo();
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            GeneratorConfigEntity generatorEntity = session.generatorConfigDao()
                    .findById(domain.domainId(), generatorId);
            if (generatorEntity == null) {
                throw new InvalidConfigException("generator config not found by id: " + generatorId);
            }
            validateGeneratorEntity(domain, generatorId, generatorEntity);

            List<WorkerEntity> workers = session.workerDao().findByGenerator(domain.domainId(), generatorId);
            if (workers == null || workers.isEmpty()) {
                throw new InvalidConfigException("worker list must not be empty, generator id: " + generatorId);
            }

            GeneratorConfig generatorConfig = toGeneratorConfig(generatorEntity);
            return toGeneratorProperties(domain, generatorConfig, workers);
        }
    }

    private IdDomainInfo loadDomainInfo() {
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            DomainEntity domainEntity = session.domainDao().findByName(domainName);
            if (domainEntity == null) {
                throw new InvalidConfigException("domain not found by name: " + domainName);
            }
            return toDomainInfo(domainEntity);
        }
    }

    private static IdDomainInfo toDomainInfo(DomainEntity entity) {
        return new IdDomainInfo(
                entity.getDomainId(),
                entity.getDomainName(),
                toPrefixType(entity.getPrefixType()),
                entity.getTimeBit(),
                entity.getWorkerBit(),
                entity.getSequenceBit(),
                entity.getEpochStartMs(),
                toTimeUnit(entity.getTimeUnit())
        );
    }

    private static GeneratorConfig toGeneratorConfig(GeneratorConfigEntity entity) {
        return new GeneratorConfig(
                entity.getGeneratorId(),
                entity.getGeneratorName(),
                toGeneratorPolicy(entity.getPolicy())
        );
    }

    private static List<GeneratorProperties> toGeneratorProperties(IdDomainInfo domain,
                                                                   GeneratorConfig generatorConfig,
                                                                   List<WorkerEntity> workers) {
        List<GeneratorProperties> properties = new ArrayList<>(workers.size());
        Set<Long> workerIds = new HashSet<>();
        for (WorkerEntity worker : workers) {
            validateWorkerEntity(domain, generatorConfig.generatorId(), worker);
            if (!workerIds.add(worker.getWorkerId())) {
                throw new InvalidConfigException("duplicated worker id from mysql: " + worker.getWorkerId());
            }
            properties.add(new GeneratorProperties(domain, generatorConfig, worker.getWorkerId(), null));
        }
        return List.copyOf(properties);
    }

    private static GeneratorPolicy toGeneratorPolicy(String policyJson) {
        if (policyJson == null || policyJson.isBlank()) {
            throw new InvalidConfigException("generator policy json must not be blank");
        }
        try {
            PolicyConfig config = JSON_MAPPER.readValue(policyJson, PolicyConfig.class);
            if (config == null) {
                throw new InvalidConfigException("generator policy json must not be empty");
            }
            return new GeneratorPolicy(
                    config.maximumRetry() == null ? DEFAULT_MAXIMUM_RETRY : config.maximumRetry(),
                    resolveIdRunOutPolicy(config.idRunOutPolicy()),
                    resolveClockRollbackPolicy(config.clockRollbackPolicy())
            );
        } catch (InvalidConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidConfigException("failed to parse generator policy json", e);
        }
    }

    private static IdRunOutPolicy resolveIdRunOutPolicy(String value) {
        if (value == null) {
            return IdRunOutPolicy.NONE;
        }
        String normalizedValue = normalizePolicyValue(value, "id run out policy");
        IdRunOutPolicy policy = ID_RUN_OUT_POLICIES.get(normalizedValue);
        if (policy == null) {
            throw new InvalidConfigException("unsupported id run out policy: " + value);
        }
        return policy;
    }

    private static ClockRollbackPolicy resolveClockRollbackPolicy(String value) {
        if (value == null) {
            return ClockRollbackPolicy.NONE;
        }
        String normalizedValue = normalizePolicyValue(value, "clock rollback policy");
        ClockRollbackPolicy policy = CLOCK_ROLLBACK_POLICIES.get(normalizedValue);
        if (policy == null) {
            throw new InvalidConfigException("unsupported clock rollback policy: " + value);
        }
        return policy;
    }

    private static String normalizePolicyValue(String value, String fieldName) {
        if (value.isBlank()) {
            throw new InvalidConfigException(fieldName + " must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static PrefixType toPrefixType(DomainPrefixType prefixType) {
        if (prefixType == null) {
            throw new InvalidConfigException("domain prefix type must not be null");
        }
        return switch (prefixType) {
            case POSITIVE_SIGNAL -> PrefixType.POSITIVE_SIGNAL;
            case NONE -> PrefixType.NONE;
        };
    }

    private static TimeUnit toTimeUnit(DomainTimeUnit timeUnit) {
        if (timeUnit == null) {
            throw new InvalidConfigException("domain time unit must not be null");
        }
        return switch (timeUnit) {
            case MILLISECOND -> TimeUnit.MILLISECOND;
            case SECOND -> TimeUnit.SECOND;
        };
    }

    private static void validateGeneratorEntity(IdDomainInfo domain,
                                                long requestedGeneratorId,
                                                GeneratorConfigEntity entity) {
        if (entity.getGeneratorId() == null || entity.getGeneratorId() != requestedGeneratorId) {
            throw new InvalidConfigException("generator id mismatch from mysql, requested: " + requestedGeneratorId);
        }
        if (!domain.domainId().equals(entity.getDomainId())) {
            throw new InvalidConfigException("generator domain mismatch from mysql, generator id: " + requestedGeneratorId);
        }
        requireText(entity.getGeneratorName(), "generator name");
    }

    private static void validateWorkerEntity(IdDomainInfo domain, long generatorId, WorkerEntity worker) {
        if (worker == null) {
            throw new InvalidConfigException("worker row must not be null");
        }
        if (!domain.domainId().equals(worker.getDomainId())) {
            throw new InvalidConfigException("worker domain mismatch from mysql, generator id: " + generatorId);
        }
        if (worker.getGeneratorId() == null || worker.getGeneratorId() != generatorId) {
            throw new InvalidConfigException("worker generator mismatch from mysql, generator id: " + generatorId);
        }
        if (worker.getWorkerId() == null) {
            throw new InvalidConfigException("worker id must not be null, generator id: " + generatorId);
        }
    }

    private static void validateGeneratorId(long generatorId) {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private record PolicyConfig(
            Integer maximumRetry,
            String idRunOutPolicy,
            String clockRollbackPolicy) {
    }
}
