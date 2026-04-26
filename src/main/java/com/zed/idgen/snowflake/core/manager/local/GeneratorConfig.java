package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.constant.GeneratorPolicy;

import java.util.List;

public record GeneratorConfig(long generatorId,
                              String generatorName,
                              Long domainId,
                              String domainName,
                              List<Long> workers,
                              GeneratorPolicy generationPolicy) {}
