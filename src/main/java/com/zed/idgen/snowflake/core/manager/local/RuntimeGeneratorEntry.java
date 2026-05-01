package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;

record RuntimeGeneratorEntry(long generatorId, String generatorName, long workerId, IdGeneratorInstance generator) {
}
