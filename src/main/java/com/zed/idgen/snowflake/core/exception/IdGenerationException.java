package com.zed.idgen.snowflake.core.exception;

import com.zed.idgen.snowflake.core.manager.GeneratorParam;

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

    public static IdGenerationException noAvailableWorker(GeneratorParam generatorParam) {
        return new IdGenerationException("no available worker for generator: " + generatorParam,
                SnowflakeErrorCode.NoAvailableWorker);
    }

    public static IdGenerationException generatorWorkerAlreadyRunning(long generatorId, long workerId) {
        return new IdGenerationException("generator worker is already running, generator id: "
                + generatorId + ", worker id: " + workerId, SnowflakeErrorCode.GeneratorAlreadyRunning);
    }

    public static IdGenerationException generatorIdConflict(long generatorId) {
        return new IdGenerationException("generator id is already running with a conflict name: " + generatorId,
                SnowflakeErrorCode.GeneratorConflict);
    }

    public static IdGenerationException generatorNameConflict(String generatorName) {
        return new IdGenerationException("generator name is already running with a conflicted id: " + generatorName,
                SnowflakeErrorCode.GeneratorConflict);
    }

    public static IdGenerationException workerAlreadyRunning(long workerId) {
        return new IdGenerationException("worker id is already running: " + workerId,
                SnowflakeErrorCode.WorkerAlreadyRunning);
    }

    public static IdGenerationException runtimeManagerClosed() {
        return new IdGenerationException("generator runtime manager is closed",
                SnowflakeErrorCode.RuntimeManagerClosed);
    }
}
