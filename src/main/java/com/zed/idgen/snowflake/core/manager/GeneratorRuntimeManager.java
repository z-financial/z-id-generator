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
     * Resolves a generator name/ref to its stable generator id.
     */
    long resolveGeneratorId(String generatorName);

    /**
     * Returns registered worker bindings under one generator id.
     */
    List<GeneratorProperties> getGeneratorProperties(long generatorId);

    /**
     * Returns all running local instances under the requested generator id,
     * keyed by worker id.
     */
    Map<Long, IdGenerator> getGeneratorInstances(long generatorId);

    /**
     * Returns the running instance for one exact worker id, or null if missing.
     */
    IdGenerator getGeneratorInstance(long generatorId, long workerId);

    /**
     * Starts one available worker under the requested generator id.
     */
    IdGenerator startGeneratorInstance(long generatorId);

    /**
     * Starts the exact worker under the requested generator id.
     */
    IdGenerator startGeneratorInstance(long generatorId, long workerId);

    /**
     * Stops the exact worker under the requested generator id.
     */
    boolean stopGeneratorInstance(long generatorId, long workerId);
}
