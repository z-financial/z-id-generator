package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.generator.IdGeneratorFactory;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
     * These maps form one logical runtime index. A generator config can have
     * many running worker instances, so byGeneratorId owns entries by
     * generatorId -> workerId. workerIdToGeneratorId is a secondary index for
     * domain-level worker uniqueness. A single ReadWriteLock keeps cross-map
     * reads and writes consistent without requiring ConcurrentHashMap.
     */
    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock();
    private final Lock readLock = runtimeLock.readLock();
    private final Lock writeLock = runtimeLock.writeLock();
    private final Map<Long, Map<Long, RuntimeGeneratorEntry>> byGeneratorId = new HashMap<>();
    private final Map<Long, Long> workerIdToGeneratorId = new HashMap<>();
    /*
     * Runtime instance ids are unique only inside this manager process. They are
     * useful for comparing different incarnations of the same generator/worker
     * without relying on object equality or mutable lifecycle state.
     */
    private final AtomicLong instanceIdSequence = new AtomicLong();
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
            return registrationLoader.getDomainInfo();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public long resolveGeneratorId(String generatorName) {
        requireGeneratorName(generatorName);
        readLock.lock();
        try {
            ensureOpen();
            return registrationLoader.resolveGeneratorId(generatorName);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<GeneratorProperties> getGeneratorProperties(long generatorId) {
        requireGeneratorId(generatorId);
        readLock.lock();
        try {
            ensureOpen();
            return registrationLoader.loadGeneratorProperties(generatorId);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Map<Long, IdGenerator> getGeneratorInstances(long generatorId) {
        requireGeneratorId(generatorId);
        readLock.lock();
        try {
            ensureOpen();
            Map<Long, RuntimeGeneratorEntry> entries = findRuntimeEntries(generatorId);
            if (entries.isEmpty()) {
                return Map.of();
            }
            Map<Long, IdGenerator> generators = new HashMap<>();
            for (RuntimeGeneratorEntry entry : entries.values()) {
                generators.put(entry.workerId(), entry.generator());
            }
            return Map.copyOf(generators);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public IdGenerator getGeneratorInstance(long generatorId, long workerId) {
        requireGeneratorId(generatorId);
        requireWorkerId(workerId);
        readLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorId, workerId);
            return entry == null ? null : entry.generator();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public IdGenerator startGeneratorInstance(long generatorId) {
        requireGeneratorId(generatorId);
        writeLock.lock();
        try {
            ensureOpen();
            List<GeneratorProperties> candidates = loadCandidateProperties(generatorId);
            for (GeneratorProperties properties : candidates) {
                if (workerIdToGeneratorId.containsKey(properties.workerId())) {
                    continue;
                }
                return createAndRegister(properties).generator();
            }
            throw IdGenerationException.noAvailableWorker(generatorId);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public IdGenerator startGeneratorInstance(long generatorId, long workerId) {
        requireGeneratorId(generatorId);
        requireWorkerId(workerId);
        writeLock.lock();
        try {
            ensureOpen();
            assertWorkerCanStart(generatorId, workerId);
            GeneratorProperties properties = findCandidateByWorkerId(loadCandidateProperties(generatorId), workerId);
            if (properties == null) {
                throw new InvalidConfigException("worker id is not registered under generator: " + workerId);
            }
            return createAndRegister(properties).generator();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean stopGeneratorInstance(long generatorId, long workerId) {
        requireGeneratorId(generatorId);
        requireWorkerId(workerId);
        writeLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorId, workerId);
            if (entry == null) {
                return false;
            }

            entry.generator().stop();
            removeEntry(entry);
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
            for (Map<Long, RuntimeGeneratorEntry> entries : byGeneratorId.values()) {
                for (RuntimeGeneratorEntry entry : entries.values()) {
                    entry.generator().stop();
                }
            }
            byGeneratorId.clear();
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
        long workerId = instanceProperties.workerId();
        assertWorkerCanStart(config.generatorId(), workerId);

        IdGeneratorInstance generator = IdGeneratorFactory.create(instanceProperties, nextInstanceId());
        RuntimeGeneratorEntry entry = new RuntimeGeneratorEntry(
                config.generatorId(),
                config.generatorName(),
                workerId,
                generator
        );

        try {
            generator.start();
            publish(entry);
            return entry;
        } catch (RuntimeException e) {
            generator.stop();
            throw e;
        }
    }

    private long nextInstanceId() {
        long instanceId = instanceIdSequence.incrementAndGet();
        if (instanceId <= 0) {
            throw new InvalidConfigException("runtime instance id overflow");
        }
        return instanceId;
    }

    private void publish(RuntimeGeneratorEntry entry) {
        assertWorkerCanStart(entry.generatorId(), entry.workerId());
        byGeneratorId.computeIfAbsent(entry.generatorId(), ignored -> new HashMap<>())
                .put(entry.workerId(), entry);
        workerIdToGeneratorId.put(entry.workerId(), entry.generatorId());
    }

    private void removeEntry(RuntimeGeneratorEntry entry) {
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(entry.generatorId());
        if (entries != null) {
            entries.remove(entry.workerId());
            if (entries.isEmpty()) {
                byGeneratorId.remove(entry.generatorId());
            }
        }
        workerIdToGeneratorId.remove(entry.workerId(), entry.generatorId());
    }

    /**
     * Returns running entries for one canonical generator id. Missing runtime
     * state is a normal query miss and returns an empty map.
     */
    private Map<Long, RuntimeGeneratorEntry> findRuntimeEntries(long generatorId) {
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(generatorId);
        return entries == null ? Map.of() : entries;
    }

    private RuntimeGeneratorEntry findRuntimeEntry(long generatorId, long workerId) {
        return findRuntimeEntries(generatorId).get(workerId);
    }

    /**
     * Checks both runtime indexes before starting a worker. The exact
     * generator/worker duplicate gets the more specific error; a worker already
     * owned by another generator is reported as a domain-level worker conflict.
     */
    private void assertWorkerCanStart(long generatorId, long workerId) {
        RuntimeGeneratorEntry entry = findRuntimeEntry(generatorId, workerId);
        if (entry != null) {
            throw IdGenerationException.generatorWorkerAlreadyRunning(generatorId, workerId);
        }
        if (workerIdToGeneratorId.containsKey(workerId)) {
            throw IdGenerationException.workerAlreadyRunning(workerId);
        }
    }

    private List<GeneratorProperties> loadCandidateProperties(long generatorId) {
        List<GeneratorProperties> candidates = registrationLoader.loadGeneratorProperties(generatorId);
        if (candidates == null || candidates.isEmpty()) {
            throw new InvalidConfigException("generator properties must not be empty, generator id: " + generatorId);
        }
        return candidates;
    }

    private static GeneratorProperties findCandidateByWorkerId(List<GeneratorProperties> candidates, long workerId) {
        for (GeneratorProperties properties : candidates) {
            if (properties.workerId() == workerId) {
                return properties;
            }
        }
        return null;
    }

    private void ensureOpen() {
        if (closed) {
            throw IdGenerationException.runtimeManagerClosed();
        }
    }

    private static void requireGeneratorId(long generatorId) {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
    }

    private static void requireGeneratorName(String generatorName) {
        if (generatorName == null || generatorName.isBlank()) {
            throw new InvalidConfigException("generator name must not be blank");
        }
    }

    private static void requireWorkerId(long workerId) {
        if (workerId < 0) {
            throw new InvalidConfigException("worker id must not be negative");
        }
    }

    private record RuntimeGeneratorEntry(long generatorId,
                                         String generatorName,
                                         long workerId,
                                         IdGeneratorInstance generator) {
    }
}
