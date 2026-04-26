package com.zed.idgen.snowflake.core.constant;

import java.util.List;

public record GeneratorInstanceProperties(
        IdDomainInfo idDomainInfo,
        GeneratorInfo generatorInfo,
        List<Long> workers) {
}
