package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.List;
import java.util.Map;

public interface GeneratorRuntimeManager {
    /**
     * Returns the stable domain definition snapshot used by this manager.
     */
    IdDomainInfo getDomainInfo();

    /**
     * Returns registered worker bindings under one generator config.
     */
    List<GeneratorProperties> getGeneratorProperties(GeneratorParam generatorParam);

    /**
     * Returns all running local instances under the requested generator config,
     * keyed by worker id.
     */
    Map<Long, IdGenerator> getGeneratorInstances(GeneratorParam generatorParam);

    /**
     * Returns the running instance for one exact worker id, or null if missing.
     */
    IdGenerator getGeneratorInstance(GeneratorParam generatorParam, long workerId);

    /**
     * Starts one available worker under the requested generator config.
     */
    IdGenerator startGeneratorInstance(GeneratorParam generatorParam);

    /**
     * Starts the exact worker under the requested generator config.
     */
    IdGenerator startGeneratorInstance(GeneratorParam generatorParam, long workerId);

    /**
     * Stops the exact worker under the requested generator config.
     */
    boolean stopGeneratorInstance(GeneratorParam generatorParam, long workerId);
}
