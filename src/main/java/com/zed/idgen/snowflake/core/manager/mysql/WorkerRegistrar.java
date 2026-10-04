package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.model.GeneratorProperties;

@FunctionalInterface
public interface WorkerRegistrar {
    GeneratorProperties registerNext(long generatorId);
}
