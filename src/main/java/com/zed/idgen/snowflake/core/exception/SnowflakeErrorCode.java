package com.zed.idgen.snowflake.core.exception;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SnowflakeErrorCode {
    InvalidConfig(1, "invalid config"),
    ClockRollback(2, "clock rollback"),
    IdRunOut(3, "id run out"),
    MaxRetryExceeded(4, "max retry exceeded"),
    GeneratorNotStarted(5, "generator not started"),
    GeneratorStopped(6, "generator stopped"),
    NoAvailableWorker(7, "no available worker"),
    GeneratorAlreadyRunning(8, "generator already running"),
    WorkerAlreadyRunning(10, "worker already running"),
    RuntimeManagerClosed(11, "runtime manager closed"),
    LeaseExpired(12, "lease expired"),;

    private final int code;
    private final String message;
}
