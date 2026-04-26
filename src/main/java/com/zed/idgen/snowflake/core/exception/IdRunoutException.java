package com.zed.idgen.snowflake.core.exception;

public class IdRunoutException extends RuntimeException {
    public IdRunoutException(String message) {
        super(message);
    }
}
