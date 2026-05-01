package com.zed.idgen.snowflake.core.exception;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SnowflakeErrorCode {
    InvalidConfig(1, "invalid config"),
    ClockRollback(2, "clock rollback"),
    IdRunOut(3, "id run out"),
    MaxRetryExceeded(4, "max retry exceeded"),
    GeneratorNotStarted(5, "generator not started"),
    GeneratorStopped(6, "generator stopped"),;

    private final int code;
    private final String message;
}
