package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorInfo;

public interface GeneratorRegistrator {
    void registerGenerator(GeneratorInfo info);

    boolean unregisterGenerator(long generatorId);
}
