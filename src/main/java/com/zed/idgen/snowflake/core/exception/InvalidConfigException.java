package com.zed.idgen.snowflake.core.exception;

public class InvalidConfigException extends RuntimeException {
    public InvalidConfigException(String message) {
        super(message);
    }
}
