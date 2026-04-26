package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;

public interface RegistrationLoader {
    GeneratorInstanceProperties loadById(long generatorId, DomainParam domainParam);

    GeneratorInstanceProperties loadByName(String generatorName, DomainParam domainParam);
}
