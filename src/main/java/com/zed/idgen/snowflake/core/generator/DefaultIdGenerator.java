package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.component.provider.IdProvider;


public class DefaultIdGenerator implements IdGenerator {
    private final IdProvider idProvider;

    DefaultIdGenerator(IdProvider idProvider) {
        if (idProvider == null) {
            throw new IllegalArgumentException("idProvider must not be null");
        }
        this.idProvider = idProvider;
    }

    @Override
    public long getUid() {
        return idProvider.nextId();
    }

    @Override
    public long[] getUidByBatch(int size) {
        return idProvider.nextIds(size);
    }
}
