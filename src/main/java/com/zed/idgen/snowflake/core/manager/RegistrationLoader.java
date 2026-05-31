package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.List;

public interface RegistrationLoader {
    /**
     * Returns the stable domain definition snapshot for this registration source.
     * Domain information defines UID bit layout and decode semantics, so it must
     * not change during the lifetime of a loader.
     */
    IdDomainInfo getDomainInfo();

    /**
     * Loads registered worker bindings under the requested generator config.
     * This method only loads registration/config data; it must not acquire
     * runtime leases or decide which worker this JVM should run.
     * Implementations may reload generator config dynamically, but returned
     * properties must still use the stable domain snapshot from getDomainInfo().
     */
    List<GeneratorProperties> loadGeneratorProperties(GeneratorParam generatorParam);
}
