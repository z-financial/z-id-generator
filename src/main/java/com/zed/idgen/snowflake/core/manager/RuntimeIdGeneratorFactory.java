package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;

@FunctionalInterface
public interface RuntimeIdGeneratorFactory {
    IdGeneratorInstance create(
            RuntimeCreateContext runtimeContext,
            GeneratorInstanceProperties properties,
            long instanceId);
}
