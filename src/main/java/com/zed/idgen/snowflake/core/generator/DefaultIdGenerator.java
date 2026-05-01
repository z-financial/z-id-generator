package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;


public class DefaultIdGenerator implements IdGeneratorInstance {
    private final IdProvider idProvider;
    private GeneratorStatus status = GeneratorStatus.INIT;

    DefaultIdGenerator(IdProvider idProvider) {
        if (idProvider == null) {
            throw new IllegalArgumentException("idProvider must not be null");
        }
        this.idProvider = idProvider;
    }

    @Override
    public synchronized void setStatus(GeneratorStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("generator status must not be null");
        }
        if (this.status == GeneratorStatus.STOPPED && status != GeneratorStatus.STOPPED) {
            throw new IllegalStateException("stopped generator cannot be restarted");
        }
        if (this.status == GeneratorStatus.RUNNING && status == GeneratorStatus.INIT) {
            throw new IllegalStateException("running generator cannot move back to init");
        }
        this.status = status;
    }

    @Override
    public synchronized long getUid() {
        requireRunning();
        return idProvider.nextId();
    }

    @Override
    public synchronized long[] getUidByBatch(int size) {
        requireRunning();
        return idProvider.nextIds(size);
    }

    private void requireRunning() {
        if (status == GeneratorStatus.INIT) {
            throw IdGenerationException.generatorNotStart();
        }
        if (status == GeneratorStatus.STOPPED) {
            throw IdGenerationException.generatorStopped();
        }
    }
}
