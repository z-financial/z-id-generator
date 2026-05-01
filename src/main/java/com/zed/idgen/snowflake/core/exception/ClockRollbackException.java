package com.zed.idgen.snowflake.core.exception;

public class ClockRollbackException extends SnowflakeException {
    public ClockRollbackException(String message) {
        super(message, SnowflakeErrorCode.ClockRollback);
    }
}
