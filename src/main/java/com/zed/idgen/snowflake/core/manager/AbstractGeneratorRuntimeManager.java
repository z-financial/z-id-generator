package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.generator.GeneratorStatus;
import com.zed.idgen.snowflake.core.generator.IdGeneratorFactory;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * generatorId -> workerId. The other maps are secondary indexes for name
     * lookup and domain-level worker uniqueness. A single ReadWriteLock keeps
     * cross-map reads and writes consistent without requiring ConcurrentHashMap.
     */
    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock();
    private final Lock readLock = runtimeLock.readLock();
    private final Lock writeLock = runtimeLock.writeLock();
    private final Map<Long, Map<Long, RuntimeGeneratorEntry>> byGeneratorId = new HashMap<>();
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
            return registrationLoader.getDomainInfo();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<GeneratorProperties> getGeneratorProperties(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        readLock.lock();
        try {
            ensureOpen();
            return registrationLoader.loadGeneratorProperties(generatorParam);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Map<Long, IdGenerator> getGeneratorInstances(GeneratorParam generatorParam) {
        requireGeneratorParam(generatorParam);
        readLock.lock();
        try {
            ensureOpen();
            Map<Long, RuntimeGeneratorEntry> entries = findRuntimeEntries(generatorParam);
            if (entries.isEmpty()) {
                return Map.of();
            }
            Map<Long, IdGenerator> generators = new LinkedHashMap<>();
            for (RuntimeGeneratorEntry entry : entries.values()) {
                generators.put(entry.workerId(), entry.generator());
            }
            return Map.copyOf(generators);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public IdGenerator getGeneratorInstance(GeneratorParam generatorParam, long workerId) {
        requireGeneratorParam(generatorParam);
        requireWorkerId(workerId);
        readLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorParam, workerId);
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
            List<GeneratorProperties> candidates = loadCandidateProperties(generatorParam);
            for (GeneratorProperties properties : candidates) {
                if (workerIdToGeneratorId.containsKey(properties.workerId())) {
                    continue;
                }
                return createAndRegister(properties).generator();
            }
            throw IdGenerationException.noAvailableWorker(generatorParam);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public IdGenerator startGeneratorInstance(GeneratorParam generatorParam, long workerId) {
        requireGeneratorParam(generatorParam);
        requireWorkerId(workerId);
        writeLock.lock();
        try {
            ensureOpen();
            assertNoLocalWorkerConflict(generatorParam, workerId);
            GeneratorProperties properties = findCandidateByWorkerId(loadCandidateProperties(generatorParam), workerId);
            if (properties == null) {
                throw new InvalidConfigException("worker id is not registered under generator: " + workerId);
            }
            return createAndRegister(properties).generator();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean stopGeneratorInstance(GeneratorParam generatorParam, long workerId) {
        requireGeneratorParam(generatorParam);
        requireWorkerId(workerId);
        writeLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorParam, workerId);
            if (entry == null) {
                return false;
            }

            entry.generator().setStatus(GeneratorStatus.STOPPED);
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
                    entry.generator().setStatus(GeneratorStatus.STOPPED);
                }
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
        long workerId = instanceProperties.workerId();
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
            generator.setStatus(GeneratorStatus.STOPPED);
            throw e;
        }
    }

    private void publish(RuntimeGeneratorEntry entry) {
        assertNoRunningConflict(entry.generatorId(), entry.generatorName(), entry.workerId());
        byGeneratorId.computeIfAbsent(entry.generatorId(), ignored -> new HashMap<>())
                .put(entry.workerId(), entry);
        generatorNameToId.putIfAbsent(entry.generatorName(), entry.generatorId());
        workerIdToGeneratorId.put(entry.workerId(), entry.generatorId());
    }

    private void removeEntry(RuntimeGeneratorEntry entry) {
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(entry.generatorId());
        if (entries != null) {
            entries.remove(entry.workerId());
            if (entries.isEmpty()) {
                byGeneratorId.remove(entry.generatorId());
                generatorNameToId.remove(entry.generatorName(), entry.generatorId());
            }
        }
        workerIdToGeneratorId.remove(entry.workerId(), entry.generatorId());
    }

    /**
     * Returns the running entries matching a generator id/name. Callers must
     * hold the runtime lock because the returned map is the live inner index.
     */
    private Map<Long, RuntimeGeneratorEntry> findRuntimeEntries(GeneratorParam generatorParam) {
        if (generatorParam == null) {
            return Map.of();
        }
        Long generatorId = generatorParam.generatorId();
        String generatorName = generatorParam.generatorName();

        if (generatorId != null) {
            Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(generatorId);
            if (entries == null) {
                return Map.of();
            }
            if (generatorName != null && !Objects.equals(generatorNameToId.get(generatorName), generatorId)) {
                return Map.of();
            }
            return entries;
        }

        Long indexedGeneratorId = generatorNameToId.get(generatorName);
        if (indexedGeneratorId == null) {
            return Map.of();
        }
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(indexedGeneratorId);
        return entries == null ? Map.of() : entries;
    }

    private RuntimeGeneratorEntry findRuntimeEntry(GeneratorParam generatorParam, long workerId) {
        return findRuntimeEntries(generatorParam).get(workerId);
    }

    private void assertNoRunningConflict(long generatorId, String generatorName, long workerId) {
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(generatorId);
        if (entries != null) {
            if (entries.containsKey(workerId)) {
                throw IdGenerationException.generatorWorkerAlreadyRunning(generatorId, workerId);
            }
            RuntimeGeneratorEntry sample = entries.values().iterator().next();
            if (!generatorName.equals(sample.generatorName())) {
                throw IdGenerationException.generatorIdConflict(generatorId);
            }
        }
        Long existingGeneratorId = generatorNameToId.get(generatorName);
        if (existingGeneratorId != null && !existingGeneratorId.equals(generatorId)) {
            throw IdGenerationException.generatorNameConflict(generatorName);
        }
        if (workerIdToGeneratorId.containsKey(workerId)) {
            throw IdGenerationException.workerAlreadyRunning(workerId);
        }
    }

    /**
     * Explicit worker start can fail fast from local runtime indexes before
     * loading registration data. It still reloads config afterward to verify
     * that the requested worker belongs to the requested generator config.
     */
    private void assertNoLocalWorkerConflict(GeneratorParam generatorParam, long workerId) {
        RuntimeGeneratorEntry entry = findRuntimeEntry(generatorParam, workerId);
        if (entry != null) {
            throw IdGenerationException.generatorWorkerAlreadyRunning(entry.generatorId(), workerId);
        }
        if (workerIdToGeneratorId.containsKey(workerId)) {
            throw IdGenerationException.workerAlreadyRunning(workerId);
        }
    }

    private List<GeneratorProperties> loadCandidateProperties(GeneratorParam generatorParam) {
        List<GeneratorProperties> candidates = registrationLoader.loadGeneratorProperties(generatorParam);
        if (candidates == null || candidates.isEmpty()) {
            throw new InvalidConfigException("generator properties must not be empty: " + generatorParam);
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

    private static void requireGeneratorParam(GeneratorParam generatorParam) {
        if (generatorParam == null) {
            throw new InvalidConfigException("generator param must not be null");
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
