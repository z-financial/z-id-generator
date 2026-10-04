package com.zed.idgen.snowflake.core.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;

import java.util.Locale;
import java.util.Map;

/** Shared semantic conversion for the generator policy JSON stored by MySQL. */
public final class GeneratorPolicyJsonUtil {
    private static final int DEFAULT_MAXIMUM_RETRY = 1;
    private static final Map<String, IdRunOutPolicy> ID_RUN_OUT_POLICIES = Map.of(
            "NONE", IdRunOutPolicy.NONE);
    private static final Map<String, ClockRollbackPolicy> CLOCK_ROLLBACK_POLICIES = Map.of(
            "NONE", ClockRollbackPolicy.NONE);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    private GeneratorPolicyJsonUtil() {
    }

    public static GeneratorPolicy fromJson(String policyJson) {
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
                    resolveClockRollbackPolicy(config.clockRollbackPolicy()));
        } catch (InvalidConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidConfigException("failed to parse generator policy json", e);
        }
    }

    public static String toJson(GeneratorPolicy policy) {
        if (policy == null) {
            throw new InvalidConfigException("generation policy must not be null");
        }
        try {
            return JSON_MAPPER.writeValueAsString(new PolicyConfig(
                    policy.maximumRetry(),
                    toJsonValue(policy.idRunOutPolicy()),
                    toJsonValue(policy.clockRollbackPolicy())));
        } catch (InvalidConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidConfigException("failed to serialize generator policy json", e);
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

    private static String toJsonValue(IdRunOutPolicy policy) {
        if (policy == null) {
            throw new InvalidConfigException("id run out policy must not be null");
        }
        return switch (policy) {
            case NONE -> "NONE";
        };
    }

    private static String toJsonValue(ClockRollbackPolicy policy) {
        if (policy == null) {
            throw new InvalidConfigException("clock rollback policy must not be null");
        }
        return switch (policy) {
            case NONE -> "NONE";
        };
    }

    private record PolicyConfig(
            Integer maximumRetry,
            String idRunOutPolicy,
            String clockRollbackPolicy) {
    }
}
