package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorProperties;

public interface RegistrationLoader {
    /**
     * Loads generator registration data. GeneratorParam locates the generator.
     */
    GeneratorProperties loadGenerator(GeneratorParam generatorParam);
}
