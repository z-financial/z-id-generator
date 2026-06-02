package com.zed.idgen.snowflake.core.exception;

public class MysqlLeaseTimeoutException extends MysqlLeaseException {
    private MysqlLeaseTimeoutException(String message, Throwable cause) {
        super(message, cause, SnowflakeErrorCode.MysqlLeaseOperationTimeout);
    }

    public static MysqlLeaseTimeoutException operationTimeout(String operation, Throwable cause) {
        return new MysqlLeaseTimeoutException("mysql lease operation timeout: " + operation, cause);
    }
}
