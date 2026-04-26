package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;

public record GeneratorPolicy(
        int maximumRetry,
        IdRunOutPolicy idRunOutPolicy,
        ClockRollbackPolicy clockRollbackPolicy) {
}
