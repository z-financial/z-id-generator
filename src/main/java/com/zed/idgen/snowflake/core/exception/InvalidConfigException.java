package com.zed.idgen.snowflake.core.exception;

public class InvalidConfigException extends SnowflakeException {
    public InvalidConfigException(String message) {
        super(message, SnowflakeErrorCode.InvalidConfig);
    }

    public InvalidConfigException(String message, Throwable cause) {
        super(message, cause, SnowflakeErrorCode.InvalidConfig);
    }
}
