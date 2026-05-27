package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

public interface RegistrationLoader {
    /**
     * Returns the stable domain definition snapshot for this registration source.
     * Domain information defines UID bit layout and decode semantics, so it must
     * not change during the lifetime of a loader.
     */
    IdDomainInfo getDomainInfo();

    /**
     * Loads generator registration data. GeneratorParam locates the generator.
     * Implementations may reload generator config dynamically, but returned
     * properties must still use the stable domain snapshot from getDomainInfo().
     */
    GeneratorProperties loadGenerator(GeneratorParam generatorParam);
}
