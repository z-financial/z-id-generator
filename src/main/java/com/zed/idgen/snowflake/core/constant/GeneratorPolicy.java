package com.zed.idgen.snowflake.core.constant;

public record GeneratorPolicy(
        int maximumRetry,
        IdRunOutPolicy idRunOutPolicy,
        ClockRollbackPolicy clockRollbackPolicy) {
}
