package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.generator.GeneratorStatus;
import com.zed.idgen.snowflake.core.generator.IdGeneratorFactory;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Shared runtime lifecycle/indexing for registration-backed generator managers.
 * Source-specific managers own how registration data is loaded; this class only
 * turns loaded GeneratorProperties into running IdGenerator instances.
 */
public abstract class AbstractGeneratorRuntimeManager implements GeneratorRuntimeManager, AutoCloseable {
    private final RegistrationLoader registrationLoader;
    /*
     * These maps form one logical runtime index. A lookup by name reads
     * generatorNameToId and then byGeneratorId, so the operation must observe a
     * consistent snapshot while start/stop updates multiple maps. A
     * ReentrantReadWriteLock lets frequent lookups share the read lock, while
     * rare lifecycle writes take the write lock and update all indexes
     * atomically. With that external lock, plain HashMap is simpler and faster
     * than paying ConcurrentHashMap costs per index while still lacking
     * cross-map atomicity.
     */
    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock();
    private final Lock readLock = runtimeLock.readLock();
    private final Lock writeLock = runtimeLock.writeLock();
    private final Map<Long, RuntimeGeneratorEntry> byGeneratorId = new HashMap<>();
    private final Map<String, Long> generatorNameToId = new HashMap<>();
    private final Map<Long, Long> workerIdToGeneratorId = new HashMap<>();
    private boolean closed;

    protected AbstractGeneratorRuntimeManager(RegistrationLoader registrationLoader) {
        if (registrationLoader == null) {
            throw new InvalidConfigException("registration loader must not be null");
        }
        this.registrationLoader = registrationLoader;
    }

    @Override
    public IdDomainInfo getDomainInfo() {
        readLock.lock();
        try {
            ensureOpen();
            return registrationLoader.loadDomain();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public GeneratorProperties getGeneratorProperties(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        readLock.lock();
        try {
            ensureOpen();
            return registrationLoader.loadGenerator(generatorParam);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public IdGenerator getGeneratorInstance(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        readLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorParam);
            return entry == null ? null : entry.generator();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public IdGenerator startGeneratorInstance(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        writeLock.lock();
        try {
            ensureOpen();
            GeneratorProperties properties = registrationLoader.loadGenerator(generatorParam);
            return createAndRegister(properties).generator();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public IdGenerator startGeneratorInstanceIfNotExists(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        writeLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry existingByParam = findRuntimeEntry(generatorParam);
            if (existingByParam != null) {
                return existingByParam.generator();
            }

            GeneratorProperties properties = registrationLoader.loadGenerator(generatorParam);
            RuntimeGeneratorEntry existingByLoadedProperties = findExistingByLoadedProperties(properties);
            if (existingByLoadedProperties != null) {
                return existingByLoadedProperties.generator();
            }

            return createAndRegister(properties).generator();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean stopGeneratorInstance(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        writeLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorParam);
            if (entry == null) {
                return false;
            }

            entry.generator().setStatus(GeneratorStatus.STOPPED);
            byGeneratorId.remove(entry.generatorId());
            generatorNameToId.remove(entry.generatorName());
            workerIdToGeneratorId.remove(entry.workerId());
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    public void shutdown() {
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            for (RuntimeGeneratorEntry entry : byGeneratorId.values()) {
                entry.generator().setStatus(GeneratorStatus.STOPPED);
            }
            byGeneratorId.clear();
            generatorNameToId.clear();
            workerIdToGeneratorId.clear();
            onShutdown();
            closed = true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Source-specific cleanup hook, for example, deleting the local placeholder
     * file. It is called while the write lock is held after all generators stop.
     */
    protected void onShutdown() {
    }

    private RuntimeGeneratorEntry createAndRegister(GeneratorProperties properties) {
        GeneratorInstanceProperties instanceProperties = GeneratorInstanceProperties.from(properties);
        GeneratorConfig config = properties.generatorConfig();
        long workerId = resolveSingleWorker(instanceProperties.workers());
        assertNoRunningConflict(config.generatorId(), config.generatorName(), workerId);

        IdGeneratorInstance generator = IdGeneratorFactory.create(instanceProperties);
        RuntimeGeneratorEntry entry = new RuntimeGeneratorEntry(
                config.generatorId(),
                config.generatorName(),
                workerId,
                generator
        );

        try {
            generator.setStatus(GeneratorStatus.RUNNING);
            publish(entry);
            return entry;
        } catch (RuntimeException e) {
            rollback(entry);
            generator.setStatus(GeneratorStatus.STOPPED);
            throw e;
        }
    }

    private void publish(RuntimeGeneratorEntry entry) {
        if (byGeneratorId.containsKey(entry.generatorId())) {
            throw new IllegalStateException("generator id is already running: " + entry.generatorId());
        }
        if (generatorNameToId.containsKey(entry.generatorName())) {
            throw new IllegalStateException("generator name is already running: " + entry.generatorName());
        }
        if (workerIdToGeneratorId.containsKey(entry.workerId())) {
            throw new IllegalStateException("worker id is already running: " + entry.workerId());
        }

        byGeneratorId.put(entry.generatorId(), entry);
        generatorNameToId.put(entry.generatorName(), entry.generatorId());
        workerIdToGeneratorId.put(entry.workerId(), entry.generatorId());
    }

    private void rollback(RuntimeGeneratorEntry entry) {
        byGeneratorId.remove(entry.generatorId());
        generatorNameToId.remove(entry.generatorName());
        workerIdToGeneratorId.remove(entry.workerId());
    }

    private RuntimeGeneratorEntry findRuntimeEntry(GeneratorParam generatorParam) {
        if (generatorParam == null) {
            return null;
        }
        Long generatorId = generatorParam.generatorId();
        String generatorName = generatorParam.generatorName();

        if (generatorId != null) {
            RuntimeGeneratorEntry entry = byGeneratorId.get(generatorId);
            if (entry == null) {
                return null;
            }
            if (generatorName != null && !generatorName.equals(entry.generatorName())) {
                return null;
            }
            return entry;
        }

        Long indexedGeneratorId = generatorNameToId.get(generatorName);
        if (indexedGeneratorId == null) {
            return null;
        }
        RuntimeGeneratorEntry entry = byGeneratorId.get(indexedGeneratorId);
        if (entry == null || !entry.generatorName().equals(generatorName)) {
            return null;
        }
        return entry;
    }

    private RuntimeGeneratorEntry findExistingByLoadedProperties(GeneratorProperties properties) {
        GeneratorConfig config = properties.generatorConfig();
        long workerId = resolveSingleWorker(properties.workerIds());

        RuntimeGeneratorEntry existingById = byGeneratorId.get(config.generatorId());
        if (existingById != null) {
            if (!existingById.generatorName().equals(config.generatorName())) {
                throw new IllegalStateException("generator id is already running: " + config.generatorId());
            }
            return existingById;
        }

        Long existingIdByName = generatorNameToId.get(config.generatorName());
        if (existingIdByName != null) {
            RuntimeGeneratorEntry existingByName = byGeneratorId.get(existingIdByName);
            if (existingByName != null) {
                throw new IllegalStateException("generator name is already running: " + config.generatorName());
            }
        }

        Long existingIdByWorker = workerIdToGeneratorId.get(workerId);
        if (existingIdByWorker != null) {
            throw new IllegalStateException("worker id is already running: " + workerId);
        }
        return null;
    }

    private void assertNoRunningConflict(long generatorId, String generatorName, long workerId) {
        if (byGeneratorId.containsKey(generatorId)) {
            throw new IllegalStateException("generator id is already running: " + generatorId);
        }
        if (generatorNameToId.containsKey(generatorName)) {
            throw new IllegalStateException("generator name is already running: " + generatorName);
        }
        if (workerIdToGeneratorId.containsKey(workerId)) {
            throw new IllegalStateException("worker id is already running: " + workerId);
        }
    }

    private static long resolveSingleWorker(List<Long> workers) {
        if (workers == null || workers.size() != 1 || workers.getFirst() == null) {
            throw new InvalidConfigException("exactly one worker is required for runtime manager");
        }
        return workers.getFirst();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("generator runtime manager is closed");
        }
    }

    private static void requireGeneratorParam(GeneratorParam generatorParam) {
        if (generatorParam == null) {
            throw new InvalidConfigException("generator param must not be null");
        }
    }

    private record RuntimeGeneratorEntry(long generatorId,
                                         String generatorName,
                                         long workerId,
                                         IdGeneratorInstance generator) {
    }
}
