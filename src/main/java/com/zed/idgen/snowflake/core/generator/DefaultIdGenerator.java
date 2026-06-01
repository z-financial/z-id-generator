package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;

import java.util.concurrent.atomic.AtomicReference;


public class DefaultIdGenerator implements IdGeneratorInstance {
    private final IdProvider idProvider;
    private final long instanceId;
    /*
     * Status is checked on every ID generation call, but state transitions are
     * rare. AtomicReference gives volatile visibility for the hot-path read and
     * CAS for legal lifecycle transitions without adding another monitor around
     * generation. Starting/stopping is not an extreme hot path, so CAS contention
     * should stay low while generation keeps only the cheap volatile read.
     */
    private final AtomicReference<GeneratorStatus> status = new AtomicReference<>(GeneratorStatus.INIT);

    DefaultIdGenerator(IdProvider idProvider, long instanceId) {
        if (idProvider == null) {
            throw new IllegalArgumentException("idProvider must not be null");
        }
        if (instanceId <= 0) {
            throw new IllegalArgumentException("instanceId must be positive");
        }
        this.idProvider = idProvider;
        this.instanceId = instanceId;
    }

    @Override
    public boolean canGenerate() {
        return status.get() == GeneratorStatus.RUNNING;
    }

    @Override
    public boolean start() {
        while (true) {
            GeneratorStatus current = status.get();
            if (current == GeneratorStatus.RUNNING) {
                return false;
            }
            if (current == GeneratorStatus.STOPPED) {
                throw IdGenerationException.generatorStopped();
            }
            if (status.compareAndSet(GeneratorStatus.INIT, GeneratorStatus.RUNNING)) {
                return true;
            }
        }
    }

    @Override
    public boolean stop() {
        while (true) {
            GeneratorStatus current = status.get();
            if (current == GeneratorStatus.STOPPED) {
                return false;
            }
            if (status.compareAndSet(current, GeneratorStatus.STOPPED)) {
                return true;
            }
        }
    }

    @Override
    public long getInstanceId() {
        return instanceId;
    }

    @Override
    public long getUid() {
        requireRunning(status.get());
        return idProvider.nextId();
    }

    @Override
    public long[] getUidByBatch(int size) {
        requireRunning(status.get());
        return idProvider.nextIds(size);
    }

    private static void requireRunning(GeneratorStatus status) {
        if (status == GeneratorStatus.INIT) {
            throw IdGenerationException.generatorNotStart();
        }
        if (status == GeneratorStatus.STOPPED) {
            throw IdGenerationException.generatorStopped();
        }
    }
}
