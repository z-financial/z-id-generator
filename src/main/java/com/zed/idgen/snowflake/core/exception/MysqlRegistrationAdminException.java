package com.zed.idgen.snowflake.core.exception;

public final class MysqlRegistrationAdminException extends SnowflakeException {
    private MysqlRegistrationAdminException(String message, SnowflakeErrorCode errorCode) {
        super(message, errorCode);
    }

    private MysqlRegistrationAdminException(
            String message,
            Throwable cause,
            SnowflakeErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public static MysqlRegistrationAdminException domainConflict(String domainName) {
        return new MysqlRegistrationAdminException(
                "domain registration conflicts with the existing definition: " + domainName,
                SnowflakeErrorCode.MysqlRegistrationConflict);
    }

    public static MysqlRegistrationAdminException missingDomainCounter(long domainId) {
        return new MysqlRegistrationAdminException(
                "canonical worker counter is missing for domain: " + domainId,
                SnowflakeErrorCode.MysqlRegistrationIntegrityFailed);
    }

    public static MysqlRegistrationAdminException operationFailed(String operation) {
        return new MysqlRegistrationAdminException(
                "mysql registration operation failed: " + operation,
                SnowflakeErrorCode.MysqlRegistrationOperationFailed);
    }

    public static MysqlRegistrationAdminException operationFailed(String operation, Throwable cause) {
        return new MysqlRegistrationAdminException(
                "mysql registration operation failed: " + operation,
                cause,
                SnowflakeErrorCode.MysqlRegistrationOperationFailed);
    }

    public static MysqlRegistrationAdminException operationTimedOut(String operation, Throwable cause) {
        return new MysqlRegistrationAdminException(
                "mysql registration operation timed out: " + operation,
                cause,
                SnowflakeErrorCode.MysqlRegistrationOperationTimeout);
    }
}
