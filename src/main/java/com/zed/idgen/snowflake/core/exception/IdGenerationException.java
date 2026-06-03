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

    public static IdGenerationException noAvailableWorker(long generatorId) {
        return new IdGenerationException("no available worker for generator id: " + generatorId,
                SnowflakeErrorCode.NoAvailableWorker);
    }

    public static IdGenerationException generatorWorkerAlreadyRunning(long generatorId, long workerId) {
        return new IdGenerationException("generator worker is already running, generator id: "
                + generatorId + ", worker id: " + workerId, SnowflakeErrorCode.GeneratorAlreadyRunning);
    }

    public static IdGenerationException workerAlreadyRunning(long workerId) {
        return new IdGenerationException("worker id is already running: " + workerId,
                SnowflakeErrorCode.WorkerAlreadyRunning);
    }

    public static IdGenerationException runtimeManagerClosed() {
        return new IdGenerationException("generator runtime manager is closed",
                SnowflakeErrorCode.RuntimeManagerClosed);
    }

    public static IdGenerationException leaseExpired() {
        return new IdGenerationException("lease expired", SnowflakeErrorCode.LeaseExpired);
    }

    public static IdGenerationException workerCandidateUnavailable(long generatorId, long workerId) {
        return new IdGenerationException("worker candidate is unavailable, generator id: "
                + generatorId + ", worker id: " + workerId, SnowflakeErrorCode.WorkerCandidateUnavailable);
    }
}
