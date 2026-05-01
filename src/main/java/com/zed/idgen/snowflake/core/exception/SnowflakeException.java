package com.zed.idgen.snowflake.core.exception;

public abstract class SnowflakeException extends RuntimeException {
    private SnowflakeErrorCode errorCode;

    public SnowflakeException(String message, SnowflakeErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public SnowflakeException(String message, Throwable t,SnowflakeErrorCode errorCode) {
        super(message, t);
        this.errorCode = errorCode;
    }

    public SnowflakeErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "SnowflakeException{" +
                "errorCode=" + errorCode +
                '}';
    }
}
