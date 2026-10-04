package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;

/** Immutable generator definition supplied to {@link RegistrationAdmin}. */
public record GeneratorRegistrationRequest(
        String generatorName,
        GeneratorPolicy generationPolicy) {

    private static final int MAX_NAME_LENGTH = 128;

    public GeneratorRegistrationRequest {
        if (generatorName == null) {
            throw new InvalidConfigException("generator name must not be null");
        }
        generatorName = generatorName.trim();
        if (generatorName.isEmpty()) {
            throw new InvalidConfigException("generator name must not be blank");
        }
        if (generatorName.length() > MAX_NAME_LENGTH) {
            throw new InvalidConfigException("generator name must not exceed 128 characters");
        }

        // Reuse the authoritative model invariants before any persistence work begins.
        new GeneratorConfig(0L, generatorName, generationPolicy);
    }
}
