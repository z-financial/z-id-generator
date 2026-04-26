package com.zed.idgen.snowflake.core.exception;

public class ClockRollbackException extends RuntimeException {
    public ClockRollbackException(String message) {
        super(message);
    }
}
