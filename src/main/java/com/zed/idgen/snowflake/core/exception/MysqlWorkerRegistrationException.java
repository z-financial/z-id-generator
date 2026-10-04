package com.zed.idgen.snowflake.core.exception;

public final class MysqlWorkerRegistrationException extends SnowflakeException {
    private MysqlWorkerRegistrationException(String message, SnowflakeErrorCode errorCode) {
        super(message, errorCode);
    }

    private MysqlWorkerRegistrationException(
            String message,
            Throwable cause,
            SnowflakeErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public static MysqlWorkerRegistrationException missingCounter(long domainId) {
        return new MysqlWorkerRegistrationException(
                "worker counter is missing for domain: " + domainId,
                SnowflakeErrorCode.MysqlWorkerCounterMissing);
    }

    public static MysqlWorkerRegistrationException capacityExhausted(long domainId, long maxWorker) {
        return new MysqlWorkerRegistrationException(
                "worker capacity is exhausted for domain: " + domainId + ", max worker: " + maxWorker,
                SnowflakeErrorCode.MysqlWorkerCapacityExhausted);
    }

    public static MysqlWorkerRegistrationException operationFailed(long generatorId, Throwable cause) {
        return new MysqlWorkerRegistrationException(
                "mysql worker registration failed for generator: " + generatorId,
                cause,
                SnowflakeErrorCode.MysqlWorkerRegistrationFailed);
    }
}
