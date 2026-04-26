package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorConfig;

public interface GeneratorRegistrator {
    void registerGenerator(GeneratorConfig config);

    boolean unregisterGenerator(long generatorId);
}
