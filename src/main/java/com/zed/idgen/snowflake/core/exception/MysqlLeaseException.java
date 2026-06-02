package com.zed.idgen.snowflake.core.exception;

public class MysqlLeaseException extends SnowflakeException {
    private MysqlLeaseException(String message, Throwable cause) {
        super(message, cause, SnowflakeErrorCode.MysqlLeaseOperationFailed);
    }

    public static MysqlLeaseException operationFailed(String operation, Throwable cause) {
        return new MysqlLeaseException("mysql lease operation failed: " + operation, cause);
    }
}
