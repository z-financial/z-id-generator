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
import java.util.Optional;
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
     * Split lock design:
     * stateLock protects the open/closed lifecycle; dataLock protects runtime
     * maps only. Keep slow loader/subclass work outside dataLock.
     *
     * State lock gates manager lifecycle. Every public/protected runtime
     * operation takes stateReadLock first so shutdown cannot race with active
     * reads or modifications. Slow registration/lease/factory work may happen
     * while holding this read lock; it only blocks shutdown, not normal reads.
     */
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    private final Lock stateReadLock = stateLock.readLock();
    private final Lock stateWriteLock = stateLock.writeLock();
    /*
     * Data lock protects the runtime indexes only. It is intentionally held for
     * short map reads/writes so slow source-specific work does not block
     * getGeneratorInstance/getGeneratorInstances under high concurrency.
     */
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();
    private final Lock dataReadLock = dataLock.readLock();
    private final Lock dataWriteLock = dataLock.writeLock();
    /*
     * These maps form one logical runtime index. A generator config can have
     * many running worker instances, so byGeneratorId owns entries by
     * generatorId -> workerId. workerIdToGeneratorId is a secondary index for
     * domain-level worker uniqueness. Access must be protected by dataLock.
     */
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

    // Domain info does not touch runtime indexes; stateReadLock only blocks shutdown.
    @Override
    public IdDomainInfo getDomainInfo() {
        stateReadLock.lock();
        try {
            ensureOpen();
            return registrationLoader.getDomainInfo();
        } finally {
            stateReadLock.unlock();
        }
    }

    // Name resolution may hit registration storage while it does not modify the runtime data map, so it deliberately avoids dataLock.
    @Override
    public long resolveGeneratorId(String generatorName) {
        requireGeneratorName(generatorName);
        stateReadLock.lock();
        try {
            ensureOpen();
            return registrationLoader.resolveGeneratorId(generatorName);
        } finally {
            stateReadLock.unlock();
        }
    }

    // Loading registration data is source-owned and can be slow; it does not modify the runtime data map, no runtime map lock is needed.
    @Override
    public List<GeneratorProperties> getGeneratorProperties(long generatorId) {
        requireGeneratorId(generatorId);
        stateReadLock.lock();
        try {
            ensureOpen();
            return registrationLoader.loadGeneratorProperties(generatorId);
        } finally {
            stateReadLock.unlock();
        }
    }

    // Runtime lookup needs stateReadLock for lifecycle and dataReadLock for a stable map snapshot.
    @Override
    public Map<Long, IdGenerator> getGeneratorInstances(long generatorId) {
        requireGeneratorId(generatorId);
        stateReadLock.lock();
        try {
            ensureOpen();
            dataReadLock.lock();
            try {
                Map<Long, RuntimeGeneratorEntry> entries = findRuntimeEntriesUnsafe(generatorId);
                if (entries.isEmpty()) {
                    return Map.of();
                }
                Map<Long, IdGenerator> generators = new HashMap<>();
                for (RuntimeGeneratorEntry entry : entries.values()) {
                    generators.put(entry.workerId(), entry.generator());
                }
                return Map.copyOf(generators);
            } finally {
                dataReadLock.unlock();
            }
        } finally {
            stateReadLock.unlock();
        }
    }

    // Exact runtime lookup reads only the runtime indexes, so dataReadLock is enough after state check.
    @Override
    public IdGenerator getGeneratorInstance(long generatorId, long workerId) {
        requireGeneratorId(generatorId);
        requireWorkerId(workerId);
        stateReadLock.lock();
        try {
            ensureOpen();
            dataReadLock.lock();
            try {
                RuntimeGeneratorEntry entry = findRuntimeEntryUnsafe(generatorId, workerId);
                return entry == null ? null : entry.generator();
            } finally {
                dataReadLock.unlock();
            }
        } finally {
            stateReadLock.unlock();
        }
    }

    /**
     * Starts one worker by scanning the source-provided candidates in order. Each
     * candidate is skipped when it is already running locally or loses a retryable
     * lease/publication race; other failures stop the workflow. If the scan is
     * exhausted, the source may durably register one new worker, which then follows
     * the same acquire, create, start, and publish path. Registration therefore
     * completes before any lease is acquired, and exhaustion is reported only after
     * both the existing candidates and the one newly registered candidate fail.
     *
     * <p>The state read lock prevents shutdown during the workflow, while runtime-map
     * locks are held only for short conflict checks and publication.</p>
     */
    @Override
    public IdGenerator startGeneratorInstance(long generatorId) {
        requireGeneratorId(generatorId);
        stateReadLock.lock();
        try {
            ensureOpen();
            List<GeneratorProperties> candidates = loadCandidateProperties(generatorId);
            for (GeneratorProperties properties : candidates) {
                // Fast pre-check with dataReadLock avoids expensive creation for workers already running locally.
                if (isWorkerRunning(properties.workerId())) {
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

            // Existing candidates are exhausted; give the source one durable registration attempt.
            Optional<GeneratorProperties> registeredCandidate = registerGeneratorProperties(generatorId);
            if (registeredCandidate.isEmpty()) {
                throw new InvalidConfigException("registered generator properties must not be null");
            }
            try {
                return createAndRegister(registeredCandidate.get()).generator();
            } catch (IdGenerationException e) {
                if (!isSkippableCandidateFailure(e)) {
                    throw e;
                }
            }
            throw IdGenerationException.noAvailableWorker(generatorId);
        } finally {
            stateReadLock.unlock();
        }
    }

    // Exact start performs an early conflict check; publish still re-checks under dataWriteLock.
    @Override
    public IdGenerator startGeneratorInstance(long generatorId, long workerId) {
        requireGeneratorId(generatorId);
        requireWorkerId(workerId);
        stateReadLock.lock();
        try {
            ensureOpen();
            assertWorkerCanStart(generatorId, workerId);
            GeneratorProperties properties = findCandidateByWorkerId(loadRegisteredProperties(generatorId), workerId);
            if (properties == null) {
                throw new InvalidConfigException("worker id is not registered under generator: " + workerId);
            }
            return createAndRegister(properties).generator();
        } finally {
            stateReadLock.unlock();
        }
    }

    @Override
    public boolean stopGeneratorInstance(long generatorId, long workerId) {
        requireGeneratorId(generatorId);
        requireWorkerId(workerId);
        RuntimeGeneratorEntry stoppedEntry;
        stateReadLock.lock();
        try {
            ensureOpen();
            dataReadLock.lock();
            try {
                // Capture the current entry under dataReadLock before stopping outside dataLock.
                stoppedEntry = findRuntimeEntryUnsafe(generatorId, workerId);
                if (stoppedEntry == null) {
                    return false;
                }
            } finally {
                dataReadLock.unlock();
            }

            /*
             * Stop outside dataLock so map locks stay limited to index access.
             * A concurrent lookup may briefly return this stopped generator;
             * generation remains safe because IdGeneratorInstance validates its
             * own lifecycle before producing ids.
             */
            stoppedEntry.generator().stop();

            dataWriteLock.lock();
            try {
                // Revalidate by instanceId before removing; the entry may have been replaced.
                if (!removeEntryUnsafe(stoppedEntry)) {
                    return false;
                }
            } finally {
                dataWriteLock.unlock();
            }
        } finally {
            stateReadLock.unlock();
        }
        notifyStop(stoppedEntry);
        return true;
    }

    /*
     * Shutdown owns stateWriteLock, so no new reads/starts/stops can enter
     * while maps are cleared. Unlike normal stop, it is acceptable to stop
     * generators under dataWriteLock here because the manager is already
     * exclusively closing and no latency-sensitive lookup can be blocked.
     */
    public void shutdown() {
        List<RuntimeGeneratorEntry> stoppedEntries = new ArrayList<>();
        stateWriteLock.lock();
        try {
            dataWriteLock.lock();
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
                dataWriteLock.unlock();
            }
        } finally {
            stateWriteLock.unlock();
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
     * file. It is called after runtime maps are cleared and both locks are
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
     * Source-specific post-scan registration hook for automatic startup.
     * The default preserves preconfigured-worker behavior for non-MySQL managers.
     */
    protected Optional<GeneratorProperties> registerGeneratorProperties(long generatorId) {
        return Optional.empty();
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
     * stop or shutdown. It is intentionally invoked outside the data lock so
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
        stateReadLock.lock();
        try {
            ensureOpen();
            dataReadLock.lock();
            try {
                List<RuntimeGeneratorEntry> entries = new ArrayList<>();
                for (Map<Long, RuntimeGeneratorEntry> workerEntries : byGeneratorId.values()) {
                    entries.addAll(workerEntries.values());
                }
                return List.copyOf(entries);
            } finally {
                dataReadLock.unlock();
            }
        } finally {
            stateReadLock.unlock();
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
        stateReadLock.lock();
        try {
            if (closed) {
                return false;
            }
            dataReadLock.lock();
            try {
                RuntimeGeneratorEntry current = findRuntimeEntryUnsafe(entry.generatorId(), entry.workerId());
                if (!sameRuntimeEntry(entry, current)) {
                    return false;
                }
                stoppedEntry = current;
            } finally {
                dataReadLock.unlock();
            }

            /*
             * Stop outside dataLock. If another thread removes/replaces this
             * worker before we reacquire dataWriteLock, removeEntryUnsafe will
             * reject the stale entry by instanceId and leave the new entry
             * untouched.
             */
            stoppedEntry.generator().stop();

            dataWriteLock.lock();
            try {
                if (!removeEntryUnsafe(stoppedEntry)) {
                    return false;
                }
            } finally {
                dataWriteLock.unlock();
            }
        } finally {
            stateReadLock.unlock();
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

    // Final publication is the authoritative conflict check after slow creation work.
    private void publish(RuntimeGeneratorEntry entry) {
        dataWriteLock.lock();
        try {
            assertWorkerCanStartUnsafe(entry.generatorId(), entry.workerId());
            byGeneratorId.computeIfAbsent(entry.generatorId(), ignored -> new HashMap<>())
                    .put(entry.workerId(), entry);
            workerIdToGeneratorId.put(entry.workerId(), entry.generatorId());
        } finally {
            dataWriteLock.unlock();
        }
    }

    /**
     * Caller must hold dataWriteLock. The instance-id check prevents stale
     * background tasks from removing a newer incarnation of the same worker.
     */
    private boolean removeEntryUnsafe(RuntimeGeneratorEntry entry) {
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(entry.generatorId());
        if (entries == null) {
            return false;
        }
        RuntimeGeneratorEntry current = entries.get(entry.workerId());
        // A stale stop/cleanup task must not remove a newer instance for the same worker.
        if (!sameRuntimeEntry(entry, current)) {
            return false;
        }
        entries.remove(entry.workerId());
        if (entries.isEmpty()) {
            byGeneratorId.remove(entry.generatorId());
        }
        workerIdToGeneratorId.remove(entry.workerId(), entry.generatorId());
        return true;
    }

    /**
     * Caller must hold dataLock. Missing runtime state is a normal query miss
     * and returns an empty map.
     */
    private Map<Long, RuntimeGeneratorEntry> findRuntimeEntriesUnsafe(long generatorId) {
        Map<Long, RuntimeGeneratorEntry> entries = byGeneratorId.get(generatorId);
        return entries == null ? Map.of() : entries;
    }

    private RuntimeGeneratorEntry findRuntimeEntryUnsafe(long generatorId, long workerId) {
        return findRuntimeEntriesUnsafe(generatorId).get(workerId);
    }

    /**
     * Checks both runtime indexes before starting a worker. The exact
     * generator/worker duplicate gets the more specific error; a worker already
     * owned by another generator is reported as a domain-level worker conflict.
     * This wrapper is for callers that do not already hold dataLock.
     */
    private void assertWorkerCanStart(long generatorId, long workerId) {
        dataReadLock.lock();
        try {
            assertWorkerCanStartUnsafe(generatorId, workerId);
        } finally {
            dataReadLock.unlock();
        }
    }

    private void assertWorkerCanStartUnsafe(long generatorId, long workerId) {
        RuntimeGeneratorEntry entry = findRuntimeEntryUnsafe(generatorId, workerId);
        if (entry != null) {
            throw IdGenerationException.generatorWorkerAlreadyRunning(generatorId, workerId);
        }
        if (workerIdToGeneratorId.containsKey(workerId)) {
            throw IdGenerationException.workerAlreadyRunning(workerId);
        }
    }

    private boolean isWorkerRunning(long workerId) {
        dataReadLock.lock();
        try {
            return workerIdToGeneratorId.containsKey(workerId);
        } finally {
            dataReadLock.unlock();
        }
    }

    private List<GeneratorProperties> loadCandidateProperties(long generatorId) {
        return requireProperties(registrationLoader.loadCandidateProperties(generatorId), generatorId);
    }

    private List<GeneratorProperties> loadRegisteredProperties(long generatorId) {
        return requireProperties(registrationLoader.loadGeneratorProperties(generatorId), generatorId);
    }

    private static List<GeneratorProperties> requireProperties(
            List<GeneratorProperties> candidates,
            long generatorId) {
        if (candidates == null) {
            throw new InvalidConfigException("generator properties must not be null, generator id: " + generatorId);
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
