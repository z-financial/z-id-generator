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

import java.util.ArrayList;
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
    private final RuntimeIdGeneratorFactory instanceFactory;
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
        this(registrationLoader, (context, properties, instanceId) ->
                IdGeneratorFactory.create(properties, instanceId));
    }

    protected AbstractGeneratorRuntimeManager(
            RegistrationLoader registrationLoader,
            RuntimeIdGeneratorFactory instanceFactory) {
        if (registrationLoader == null) {
            throw new InvalidConfigException("registration loader must not be null");
        }
        if (instanceFactory == null) {
            throw new InvalidConfigException("runtime id generator factory must not be null");
        }
        this.registrationLoader = registrationLoader;
        this.instanceFactory = instanceFactory;
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
                try {
                    return createAndRegister(properties).generator();
                } catch (IdGenerationException e) {
                    // A candidate can lose a local race or fail DB lease acquire; try the next worker.
                    if (isSkippableCandidateFailure(e)) {
                        continue;
                    }
                    throw e;
                }
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
        RuntimeGeneratorEntry stoppedEntry;
        writeLock.lock();
        try {
            ensureOpen();
            RuntimeGeneratorEntry entry = findRuntimeEntry(generatorId, workerId);
            if (entry == null) {
                return false;
            }

            entry.generator().stop();
            removeEntry(entry);
            stoppedEntry = entry;
        } finally {
            writeLock.unlock();
        }
        notifyStop(stoppedEntry);
        return true;
    }

    public void shutdown() {
        List<RuntimeGeneratorEntry> stoppedEntries = new ArrayList<>();
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            for (Map<Long, RuntimeGeneratorEntry> entries : byGeneratorId.values()) {
                for (RuntimeGeneratorEntry entry : entries.values()) {
                    entry.generator().stop();
                    stoppedEntries.add(entry);
                }
            }
            byGeneratorId.clear();
            workerIdToGeneratorId.clear();
            closed = true;
        } finally {
            writeLock.unlock();
        }
        onShutdown();
        for (RuntimeGeneratorEntry entry : stoppedEntries) {
            notifyStop(entry);
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Source-specific cleanup hook, for example, deleting the local placeholder
     * file. It is called after runtime maps are cleared and the write lock is
     * released, so cleanup can do I/O without blocking manager lookups.
     */
    protected void onShutdown() {
    }

    /**
     * Hook for source-specific resources needed before a generator is created.
     * MySQL mode uses this to acquire a worker lease. Local/config modes keep
     * the empty default because creation is purely in-memory.
     */
    protected RuntimeCreateContext beforeCreate(GeneratorInstanceProperties properties) {
        return RuntimeCreateContext.empty();
    }

    /**
     * Lets subclasses attach stable runtime state to the published entry. The
     * attachment is later available to renewal/cleanup code and stop hooks.
     */
    protected RuntimeGeneratorEntry attachRuntime(
            RuntimeCreateContext context,
            RuntimeGeneratorEntry entry) {
        return entry;
    }

    /**
     * Called when create/start/publish fails after beforeCreate succeeded.
     * Implementations should best-effort clean resources from the context.
     */
    protected void onCreateFailure(
            RuntimeCreateContext context,
            GeneratorInstanceProperties properties) {
    }

    /**
     * Called after an entry is removed from the runtime maps during explicit
     * stop or shutdown. It is intentionally invoked outside the runtime lock so
     * source-specific cleanup, such as DB lease release, does not block lookups.
     */
    protected void onStopGenerator(
            RuntimeCreateContext context,
            GeneratorInstanceProperties properties) {
    }

    /**
     * Returns an immutable snapshot of running entries for background tasks.
     * The snapshot avoids exposing the mutable runtime indexes.
     */
    protected List<RuntimeGeneratorEntry> listRuntimeGeneratorEntries() {
        readLock.lock();
        try {
            ensureOpen();
            List<RuntimeGeneratorEntry> entries = new ArrayList<>();
            for (Map<Long, RuntimeGeneratorEntry> workerEntries : byGeneratorId.values()) {
                entries.addAll(workerEntries.values());
            }
            return List.copyOf(entries);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Stale-safe stop used by background tasks. It only removes the entry when
     * the current map value is the same runtime incarnation.
     */
    protected boolean stopGeneratorInstance(RuntimeGeneratorEntry entry) {
        if (entry == null) {
            return false;
        }
        RuntimeGeneratorEntry stoppedEntry = null;
        writeLock.lock();
        try {
            if (closed) {
                return false;
            }
            RuntimeGeneratorEntry current = findRuntimeEntry(entry.generatorId(), entry.workerId());
            if (!sameRuntimeEntry(entry, current)) {
                return false;
            }
            current.generator().stop();
            removeEntry(current);
            stoppedEntry = current;
        } finally {
            writeLock.unlock();
        }
        notifyStop(stoppedEntry);
        return true;
    }

    private RuntimeGeneratorEntry createAndRegister(GeneratorProperties properties) {
        GeneratorInstanceProperties instanceProperties = GeneratorInstanceProperties.from(properties);
        GeneratorConfig config = properties.generatorConfig();
        long workerId = instanceProperties.workerId();
        assertWorkerCanStart(config.generatorId(), workerId);

        RuntimeCreateContext runtimeContext = RuntimeCreateContext.empty();
        IdGeneratorInstance generator = null;
        try {
            // Subclasses can acquire external resources, such as a MySQL lease, before creation.
            runtimeContext = beforeCreate(instanceProperties);
            if (runtimeContext == null) {
                runtimeContext = RuntimeCreateContext.empty();
            }
            long instanceId = nextInstanceId();
            generator = instanceFactory.create(runtimeContext, instanceProperties, instanceId);
            RuntimeGeneratorEntry entry = new RuntimeGeneratorEntry(
                    config.generatorId(),
                    config.generatorName(),
                    workerId,
                    instanceId,
                    instanceProperties,
                    generator,
                    null
            );
            // Attach stable runtime state after the generator exists but before publication.
            entry = attachRuntime(runtimeContext, entry);
            if (entry == null) {
                throw new InvalidConfigException("runtime entry must not be null");
            }
            generator.start();
            publish(entry);
            return entry;
        } catch (RuntimeException e) {
            if (generator != null) {
                generator.stop();
            }
            try {
                onCreateFailure(runtimeContext, instanceProperties);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
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

    private static boolean isSkippableCandidateFailure(IdGenerationException exception) {
        return switch (exception.getErrorCode()) {
            case GeneratorAlreadyRunning, WorkerAlreadyRunning, WorkerCandidateUnavailable -> true;
            default -> false;
        };
    }

    private static boolean sameRuntimeEntry(RuntimeGeneratorEntry expected, RuntimeGeneratorEntry current) {
        return current != null
                && expected.generatorId() == current.generatorId()
                && expected.workerId() == current.workerId()
                && expected.instanceId() == current.instanceId();
    }

    private void notifyStop(RuntimeGeneratorEntry entry) {
        if (entry == null) {
            return;
        }
        onStopGenerator(new RuntimeCreateContext(entry.attachment()), entry.instanceProperties());
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

    protected record RuntimeGeneratorEntry(long generatorId,
                                           String generatorName,
                                           long workerId,
                                           long instanceId,
                                           GeneratorInstanceProperties instanceProperties,
                                           IdGeneratorInstance generator,
                                           Object attachment) {
        public RuntimeGeneratorEntry withAttachment(Object attachment) {
            return new RuntimeGeneratorEntry(
                    generatorId,
                    generatorName,
                    workerId,
                    instanceId,
                    instanceProperties,
                    generator,
                    attachment);
        }
    }
}
