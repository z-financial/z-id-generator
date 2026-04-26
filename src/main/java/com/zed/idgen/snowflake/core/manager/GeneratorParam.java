package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Lookup parameter for generator registration. The caller may provide id,
 * name, or both; blank generatorName is normalized to null.
 */
public record GeneratorParam(Long generatorId, String generatorName) {
    public GeneratorParam {
        if (generatorId != null && generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
        if (generatorName != null) {
            generatorName = generatorName.trim();
            if (generatorName.isBlank()) {
                generatorName = null;
            }
        }
        if (generatorId == null && generatorName == null) {
            throw new InvalidConfigException("generator id and generator name must not both be empty");
        }
    }
}
