package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

public interface RegistrationLoader {
    /**
     * Loads the domain definition for this registration source.
     */
    IdDomainInfo loadDomain();

    /**
     * Loads generator registration data. GeneratorParam locates the generator.
     */
    GeneratorProperties loadGenerator(GeneratorParam generatorParam);
}
