package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

public interface GeneratorRuntimeManager {
    IdDomainInfo getDomainInfo();

    GeneratorProperties getGeneratorProperties(GeneratorParam generatorParam);

    IdGenerator getGeneratorInstance(GeneratorParam generatorParam);

    IdGenerator startGeneratorInstance(GeneratorParam generatorParam);

    IdGenerator startGeneratorInstanceIfNotExists(GeneratorParam generatorParam);

    boolean stopGeneratorInstance(GeneratorParam generatorParam);
}
