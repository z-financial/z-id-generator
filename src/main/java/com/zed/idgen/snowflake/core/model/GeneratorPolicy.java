package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

public record GeneratorPolicy(
        int maximumRetry,
        IdRunOutPolicy idRunOutPolicy,
        ClockRollbackPolicy clockRollbackPolicy) {

    public GeneratorPolicy {
        if (maximumRetry < 0) {
            throw new InvalidConfigException("maximum retry must not be negative");
        }
        if (idRunOutPolicy == null) {
            throw new InvalidConfigException("id run out policy must not be null");
        }
        if (clockRollbackPolicy == null) {
            throw new InvalidConfigException("clock rollback policy must not be null");
        }
    }
}
