package com.zed.idgen.snowflake.core.exception;

public abstract class SnowflakeException extends RuntimeException {
    public SnowflakeException(String message) {
        super(message);
    }
}
