package com.zed.idgen.snowflake.core.model;

import java.util.List;

public record GeneratorInstanceProperties(
        IdDomainInfo idDomainInfo,
        GeneratorInfo generatorInfo,
        List<Long> workers) {

    public GeneratorInstanceProperties {
        if (workers != null) {
            workers = List.copyOf(workers);
        }
    }
}
