package com.zed.idgen.snowflake.core.component.provider;

public interface IdProvider {
    long nextId();
    long[] nextIds(int size);

}
