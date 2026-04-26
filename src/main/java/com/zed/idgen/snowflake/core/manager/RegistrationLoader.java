package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.constant.GeneratorInstanceProperties;

public interface RegistrationLoader {
    GeneratorInstanceProperties loadById(long generatorId);

    GeneratorInstanceProperties loadByName(String generatorName);
}
