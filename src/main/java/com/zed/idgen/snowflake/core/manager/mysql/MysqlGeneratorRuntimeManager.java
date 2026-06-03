package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.generator.IdGeneratorFactory;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.generator.LeaseInfo;
import com.zed.idgen.snowflake.core.generator.LeasedIdGenerator;
import com.zed.idgen.snowflake.core.manager.AbstractGeneratorRuntimeManager;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.manager.RuntimeCreateContext;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Distributed runtime manager for MySQL registration plus MySQL worker leases.
 * <p>
 * The base runtime manager still owns local generator maps. This class only
 * adds lease acquisition before creation, lease renewal for published entries,
 * and cleanup when a local lease deadline is lost.
 */
public class MysqlGeneratorRuntimeManager extends AbstractGeneratorRuntimeManager {
    private static final Logger LOGGER = Logger.getLogger(MysqlGeneratorRuntimeManager.class.getName());
    private static final int OWNER_TOKEN_MAX_LENGTH = 64;

    private final LeaseManager leaseManager;
    private final String serverInstanceId;
    private final MysqlGeneratorRuntimeConfig runtimeConfig;
    /*
     * Local monotonic lease guard. This is intentionally shorter than the DB
     * lease term so generation stops locally before MySQL can expire the lease.
     */
    private final long localSafeTermNanos;
    /*
     * Single scheduler that periodically snapshots running entries and submits
     * renew work. It does not perform DB calls itself.
     */
    private final ScheduledExecutorService renewExecutor;
    /*
     * Independent cleanup scheduler. Even if renew calls are slow or failing,
     * this loop removes generators whose local safe deadline has passed.
     */
    private final ScheduledExecutorService cleanerExecutor;
    /*
     * Bounded worker pool for MySQL renew SQL. A bounded queue prevents renewal
     * backlog from growing without limit when the database is slow.
     */
    private final ThreadPoolExecutor renewWorkerExecutor;
    /*
     * Backpressure flag set by the reject handler. Renew scheduling pauses
     * while the worker queue is nearly full and resumes once capacity recovers.
     */
    private final AtomicBoolean renewSaturated = new AtomicBoolean(false);

    public MysqlGeneratorRuntimeManager(
            MysqlDaoFactory daoFactory,
            String domainName,
            String serverInstanceId) {
        this(daoFactory, domainName, serverInstanceId, MysqlGeneratorRuntimeConfig.defaults());
    }

    public MysqlGeneratorRuntimeManager(
            MysqlDaoFactory daoFactory,
            String domainName,
            String serverInstanceId,
            MysqlGeneratorRuntimeConfig runtimeConfig) {
        this(
                new MysqlRegistrationLoader(daoFactory, domainName),
                new MysqlLeaseManager(daoFactory),
                serverInstanceId,
                runtimeConfig);
    }

    public MysqlGeneratorRuntimeManager(
            RegistrationLoader registrationLoader,
            LeaseManager leaseManager,
            String serverInstanceId,
            MysqlGeneratorRuntimeConfig runtimeConfig) {
        super(registrationLoader, MysqlGeneratorRuntimeManager::createLeasedGenerator);
        if (leaseManager == null) {
            throw new InvalidConfigException("lease manager must not be null");
        }
        this.leaseManager = leaseManager;
        this.serverInstanceId = requireServerInstanceId(serverInstanceId);
        this.runtimeConfig = runtimeConfig == null ? MysqlGeneratorRuntimeConfig.defaults() : runtimeConfig;
        this.localSafeTermNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                this.runtimeConfig.localSafeTermMillis());
        this.renewWorkerExecutor = newRenewWorkerExecutor(this.runtimeConfig);
        this.renewExecutor = Executors.newSingleThreadScheduledExecutor(
                namedThreadFactory("mysql-id-renew-scheduler"));
        this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(
                namedThreadFactory("mysql-id-lease-cleaner"));
        startBackgroundTasks();
    }

    @Override
    protected RuntimeCreateContext beforeCreate(GeneratorInstanceProperties properties) {
        LeaseRequest request = new LeaseRequest(
                properties.idDomainInfo().domainName(),
                properties.workerId(),
                leaseDescription(properties),
                serverInstanceId,
                newOwnerToken(),
                runtimeConfig.leaseTermSeconds());
        Optional<LeaseHandle> leaseHandle = leaseManager.tryAcquire(request);
        if (leaseHandle.isEmpty()) {
            throw IdGenerationException.workerCandidateUnavailable(
                    properties.generatorId(), properties.workerId());
        }
        return new RuntimeCreateContext(new MysqlRuntimeAttachment(
                leaseHandle.get(),
                new AtomicBoolean(false),
                localSafeTermNanos));
    }

    @Override
    protected RuntimeGeneratorEntry attachRuntime(
            RuntimeCreateContext context,
            RuntimeGeneratorEntry entry) {
        return entry.withAttachment(context.attachment());
    }

    @Override
    protected void onCreateFailure(
            RuntimeCreateContext context,
            GeneratorInstanceProperties properties) {
        releaseQuietly(context);
    }

    @Override
    protected void onStopGenerator(
            RuntimeCreateContext context,
            GeneratorInstanceProperties properties) {
        releaseQuietly(context);
    }

    @Override
    protected void onShutdown() {
        renewExecutor.shutdownNow();
        cleanerExecutor.shutdownNow();
        renewWorkerExecutor.shutdownNow();
    }

    private void startBackgroundTasks() {
        renewExecutor.scheduleWithFixedDelay(
                this::scheduleRenewTasks,
                runtimeConfig.renewIntervalMillis(),
                runtimeConfig.renewIntervalMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        cleanerExecutor.scheduleWithFixedDelay(
                this::cleanExpiredGenerators,
                runtimeConfig.cleanerIntervalMillis(),
                runtimeConfig.cleanerIntervalMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void scheduleRenewTasks() {
        if (renewSaturated.get()) {
            if (renewQueueTooFull()) {
                // stop executing new tasks when saturated and queue is too full
                return;
            }
            renewSaturated.set(false);
        }
        try {
            List<RuntimeGeneratorEntry> entries = listRuntimeGeneratorEntries();
            for (RuntimeGeneratorEntry entry : entries) {
                submitRenewTask(entry);
            }
        } catch (IdGenerationException e) {
            // Shutdown can close the manager while a scheduled tick is racing.
            LOGGER.log(Level.FINE, "skip mysql lease renew tick: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "mysql lease renew scheduler failed", e);
        }
    }

    private void submitRenewTask(RuntimeGeneratorEntry entry) {
        MysqlRuntimeAttachment attachment = mysqlAttachment(entry.attachment());
        /*
         * One in-flight renewal per runtime entry prevents overlapping renew
         * tasks from amplifying DB load when a previous renew is slow.
         */
        if (!attachment.renewInFlight().compareAndSet(false, true)) {
            return;
        }
        try {
            renewWorkerExecutor.execute(() -> renewRuntimeEntry(entry));
        } catch (RuntimeException e) {
            attachment.renewInFlight().set(false);
            throw e;
        }
    }

    private void renewRuntimeEntry(RuntimeGeneratorEntry entry) {
        MysqlRuntimeAttachment attachment = mysqlAttachment(entry.attachment());
        try {
            if (!(entry.generator() instanceof LeasedIdGenerator generator)) {
                LOGGER.warning("mysql runtime entry is not a leased generator, stopping it");
                stopGeneratorInstance(entry);
                return;
            }
            if (!generator.canGenerate()) {
                stopGeneratorInstance(entry);
                return;
            }

            boolean renewed;
            try {
                renewed = leaseManager.renew(attachment.leaseHandle());
            } catch (RuntimeException e) {
                /*
                 * A transient DB failure does not immediately prove stale
                 * ownership. Do not extend the local deadline; the cleaner will
                 * stop generation if renewals keep failing.
                 */
                LOGGER.log(Level.WARNING, "mysql lease renew failed, waiting for local deadline", e);
                return;
            }
            if (!renewed) {
                // affectedRows == 0 means the lease handle is stale or ownership was lost.
                stopGeneratorInstance(entry);
                return;
            }

            try {
                generator.renewLocalDeadline(System.nanoTime() + attachment.localSafeTermNanos());
            } catch (RuntimeException e) {
                stopGeneratorInstance(entry);
            }
        } finally {
            attachment.renewInFlight().set(false);
        }
    }

    private void cleanExpiredGenerators() {
        try {
            for (RuntimeGeneratorEntry entry : listRuntimeGeneratorEntries()) {
                if (entry.generator() instanceof LeasedIdGenerator generator
                        && generator.stopIfLocalExpired()) {
                    stopGeneratorInstance(entry);
                }
            }
        } catch (IdGenerationException e) {
            LOGGER.log(Level.FINE, "skip mysql lease cleanup tick: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "mysql lease cleanup failed", e);
        }
    }

    private void releaseQuietly(RuntimeCreateContext context) {
        if (context == null || context.attachment() == null) {
            return;
        }
        try {
            leaseManager.release(mysqlAttachment(context.attachment()).leaseHandle());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "mysql lease release failed; DB lease will expire naturally", e);
        }
    }

    private String newOwnerToken() {
        String token = serverInstanceId + ":" + UUID.randomUUID();
        if (token.length() > OWNER_TOKEN_MAX_LENGTH) {
            throw new InvalidConfigException("server instance id is too long for mysql owner token");
        }
        return token;
    }

    private static IdGeneratorInstance createLeasedGenerator(
            RuntimeCreateContext context,
            GeneratorInstanceProperties properties,
            long instanceId) {
        MysqlRuntimeAttachment attachment = mysqlAttachment(context.attachment());
        IdGeneratorInstance delegate = IdGeneratorFactory.create(properties, instanceId);
        LeaseHandle leaseHandle = attachment.leaseHandle();
        LeaseInfo leaseInfo = new LeaseInfo(
                leaseHandle.leaseDomain(),
                leaseHandle.leaseRef(),
                leaseHandle.ownerId(),
                leaseHandle.ownerToken(),
                leaseHandle.fencingToken());
        return new LeasedIdGenerator(
                delegate,
                System.nanoTime() + attachment.localSafeTermNanos(),
                leaseInfo);
    }

    private ThreadPoolExecutor newRenewWorkerExecutor(MysqlGeneratorRuntimeConfig config) {
        return new ThreadPoolExecutor(
                config.renewCorePoolSize(),
                config.renewMaxPoolSize(),
                60L,
                java.util.concurrent.TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.renewQueueSize()),
                namedThreadFactory("mysql-id-renew-worker"),
                (runnable, executor) -> {
                    renewSaturated.set(true);
                    LOGGER.warning("mysql lease renew task discarded because renew executor is saturated");
                    /*
                     * Throw after marking backpressure so submitRenewTask can
                     * clear the per-entry renewInFlight guard. Silent discard
                     * would leave that entry unable to renew again.
                     */
                    throw new RejectedExecutionException("mysql lease renew executor is saturated");
                });
    }

    private boolean renewQueueTooFull() {
        int currentSize = renewWorkerExecutor.getQueue().size();
        int remainingCapacity = renewWorkerExecutor.getQueue().remainingCapacity();
        int totalCapacity = currentSize + remainingCapacity;
        int minimumFreeSlots = Math.max(1, totalCapacity / 10);
        return remainingCapacity < minimumFreeSlots;
    }

    private static ThreadFactory namedThreadFactory(String namePrefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static MysqlRuntimeAttachment mysqlAttachment(Object attachment) {
        if (!(attachment instanceof MysqlRuntimeAttachment mysqlAttachment)) {
            throw new InvalidConfigException("mysql runtime attachment is missing");
        }
        return mysqlAttachment;
    }

    private static String leaseDescription(GeneratorInstanceProperties properties) {
        return properties.idDomainInfo().domainName()
                + ":" + properties.generatorName()
                + ":" + properties.workerId();
    }

    private static String requireServerInstanceId(String serverInstanceId) {
        if (serverInstanceId == null || serverInstanceId.isBlank()) {
            throw new InvalidConfigException("server instance id must not be blank");
        }
        return serverInstanceId.trim();
    }

    private record MysqlRuntimeAttachment(
            LeaseHandle leaseHandle,
            AtomicBoolean renewInFlight,
            long localSafeTermNanos) {
    }
}
