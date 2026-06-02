package com.zed.idgen.snowflake.core.exception;

public class MysqlLeaseException extends SnowflakeException {
    public MysqlLeaseException(String message, Throwable cause) {
        this(message, cause, SnowflakeErrorCode.MysqlLeaseOperationFailed);
    }

    protected MysqlLeaseException(String message, Throwable cause, SnowflakeErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public static MysqlLeaseException operationFailed(String operation, Throwable cause) {
        return new MysqlLeaseException("mysql lease operation failed: " + operation, cause);
    }
}
