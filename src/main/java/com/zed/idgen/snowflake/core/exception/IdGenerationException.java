package com.zed.idgen.snowflake.core.exception;

public class IdGenerationException extends SnowflakeException {
    private IdGenerationException(String message, SnowflakeErrorCode errorCode) {
        super(message, errorCode);
    }

    public static IdGenerationException maxRetryExceeded() {
        return new IdGenerationException("max retry exceeded", SnowflakeErrorCode.MaxRetryExceeded);
    }

    public static IdGenerationException generatorNotStart() {
        return new IdGenerationException("generator not start", SnowflakeErrorCode.GeneratorNotStarted);
    }

    public static IdGenerationException generatorStopped() {
        return new IdGenerationException("generator stopped", SnowflakeErrorCode.GeneratorStopped);
    }

    public static IdGenerationException idRunout() {
        throw new IdGenerationException("id runout", SnowflakeErrorCode.IdRunOut);
    }
}
