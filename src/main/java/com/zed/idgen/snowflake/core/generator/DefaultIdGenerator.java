package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;

import java.util.concurrent.atomic.AtomicReference;


public class DefaultIdGenerator implements IdGeneratorInstance {
    private final IdProvider idProvider;
    /*
     * Status is checked on every ID generation call, but state transitions are
     * rare. AtomicReference gives volatile visibility for the hot-path read and
     * CAS for legal lifecycle transitions without adding another monitor before
     * the already-synchronized IdProvider. Because setStatus is not an extreme
     * hot path, CAS contention should stay low and avoid the performance cost of
     * serializing all generation calls on this generator object.
     */
    private final AtomicReference<GeneratorStatus> status = new AtomicReference<>(GeneratorStatus.INIT);

    DefaultIdGenerator(IdProvider idProvider) {
        if (idProvider == null) {
            throw new IllegalArgumentException("idProvider must not be null");
        }
        this.idProvider = idProvider;
    }

    @Override
    public void setStatus(GeneratorStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("generator status must not be null");
        }

        while (true) {
            GeneratorStatus current = this.status.get();
            validateTransition(current, status);
            if (current == status || this.status.compareAndSet(current, status)) {
                return;
            }
        }
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

    private static void validateTransition(GeneratorStatus current, GeneratorStatus target) {
        if (current == GeneratorStatus.STOPPED && target != GeneratorStatus.STOPPED) {
            throw new IllegalStateException("stopped generator cannot be restarted");
        }
        if (current == GeneratorStatus.RUNNING && target == GeneratorStatus.INIT) {
            throw new IllegalStateException("running generator cannot move back to init");
        }
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
