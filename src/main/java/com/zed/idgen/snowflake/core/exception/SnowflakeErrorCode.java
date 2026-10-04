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
    LeaseExpired(12, "lease expired"),
    MysqlLeaseOperationFailed(13, "mysql lease operation failed"),
    MysqlLeaseOperationTimeout(14, "mysql lease operation timeout"),
    WorkerCandidateUnavailable(15, "worker candidate unavailable"),
    MysqlWorkerCounterMissing(16, "mysql worker counter missing"),
    MysqlWorkerCapacityExhausted(17, "mysql worker capacity exhausted"),
    MysqlWorkerRegistrationFailed(18, "mysql worker registration failed"),;

    private final int code;
    private final String message;
}
