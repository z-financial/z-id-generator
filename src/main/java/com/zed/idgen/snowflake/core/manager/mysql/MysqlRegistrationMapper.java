package com.zed.idgen.snowflake.core.manager.mysql;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.DomainRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import com.zed.idgen.snowflake.core.manager.mysql.enums.DomainPrefixType;
import com.zed.idgen.snowflake.core.manager.mysql.enums.DomainTimeUnit;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.Locale;
import java.util.Map;

final class MysqlRegistrationMapper {
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

    private MysqlRegistrationMapper() {
    }

    static IdDomainInfo toDomainInfo(DomainEntity entity) {
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

    static DomainEntity toDomainEntity(DomainRegistrationRequest request) {
        return new DomainEntity(
                null,
                request.domainName(),
                toDomainPrefixType(request.prefixType()),
                request.timeBit(),
                request.workerBit(),
                request.sequenceBit(),
                request.epochStartMs(),
                toDomainTimeUnit(request.timeUnit()),
                null,
                null);
    }

    static GeneratorConfig toGeneratorConfig(GeneratorConfigEntity entity) {
        return new GeneratorConfig(
                entity.getGeneratorId(),
                entity.getGeneratorName(),
                toGeneratorPolicy(entity.getPolicy())
        );
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

    private static DomainPrefixType toDomainPrefixType(PrefixType prefixType) {
        return switch (prefixType) {
            case POSITIVE_SIGNAL -> DomainPrefixType.POSITIVE_SIGNAL;
            case NONE -> DomainPrefixType.NONE;
        };
    }

    private static DomainTimeUnit toDomainTimeUnit(TimeUnit timeUnit) {
        return switch (timeUnit) {
            case MILLISECOND -> DomainTimeUnit.MILLISECOND;
            case SECOND -> DomainTimeUnit.SECOND;
        };
    }

    private record PolicyConfig(
            Integer maximumRetry,
            String idRunOutPolicy,
            String clockRollbackPolicy) {
    }
}
