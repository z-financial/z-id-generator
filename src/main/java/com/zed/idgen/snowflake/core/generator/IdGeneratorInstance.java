package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.IdGenerator;

public interface IdGeneratorInstance extends IdGenerator {
    void setStatus(GeneratorStatus status);
}
