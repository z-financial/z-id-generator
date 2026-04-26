package com.zed.idgen.snowflake.core;

public interface IdGenerator {
    long getUid();
    long[] getUidByBatch(int size);
}
