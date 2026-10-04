package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlLeaseException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.exception.SnowflakeException;
import com.zed.idgen.snowflake.core.generator.IdGeneratorFactory;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.generator.LeaseInfo;
import com.zed.idgen.snowflake.core.generator.LeasedIdGenerator;
import com.zed.idgen.snowflake.core.manager.AbstractGeneratorRuntimeManager;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.manager.RuntimeCreateContext;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final WorkerRegistrar NO_DYNAMIC_REGISTRATION = generatorId -> {
        throw new IllegalStateException("dynamic worker registration is not configured");
    };

    private final LeaseManager leaseManager;
    private final WorkerRegistrar workerRegistrar;
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
    /*
     * First unexpected background failure that forced this manager to close.
     * Expected lease/runtime outcomes are handled inside task bodies; anything
     * reaching afterExecute is treated as an unsafe state and fails closed.
     */
    private final AtomicReference<Throwable> shutdownFor = new AtomicReference<>();

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
                new MysqlWorkerRegistrar(daoFactory, domainName),
                serverInstanceId,
                runtimeConfig);
    }

    public MysqlGeneratorRuntimeManager(
            RegistrationLoader registrationLoader,
            LeaseManager leaseManager,
            String serverInstanceId,
            MysqlGeneratorRuntimeConfig runtimeConfig) {
        this(registrationLoader, leaseManager, NO_DYNAMIC_REGISTRATION, serverInstanceId, runtimeConfig);
    }

    public MysqlGeneratorRuntimeManager(
            RegistrationLoader registrationLoader,
            LeaseManager leaseManager,
            WorkerRegistrar workerRegistrar,
            String serverInstanceId,
            MysqlGeneratorRuntimeConfig runtimeConfig) {
        super(registrationLoader, MysqlGeneratorRuntimeManager::createLeasedGenerator);
        if (leaseManager == null) {
            throw new InvalidConfigException("lease manager must not be null");
        }
        if (workerRegistrar == null) {
            throw new InvalidConfigException("worker registrar must not be null");
        }
        this.leaseManager = leaseManager;
        this.workerRegistrar = workerRegistrar;
        this.serverInstanceId = requireServerInstanceId(serverInstanceId);
        this.runtimeConfig = runtimeConfig == null ? MysqlGeneratorRuntimeConfig.defaults() : runtimeConfig;
        this.localSafeTermNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                this.runtimeConfig.localSafeTermMillis());
        this.renewWorkerExecutor = newRenewWorkerExecutor(this.runtimeConfig);
        this.renewExecutor = newBackgroundScheduler("mysql-id-renew-scheduler");
        this.cleanerExecutor = newBackgroundScheduler("mysql-id-lease-cleaner");
        startBackgroundTasks();
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
                attachment.initialLocalExpireAtNanos(),
                leaseInfo);
    }


    @Override
    protected RuntimeCreateContext beforeCreate(GeneratorInstanceProperties properties) {
        long reuseDelayMillis = runtimeConfig.reuseDelayMillis(properties.idDomainInfo().timeUnit());
        LeaseRequest request = new LeaseRequest(
                properties.idDomainInfo().domainName(),
                properties.workerId(),
                leaseDescription(properties),
                serverInstanceId,
                newOwnerToken(),
                runtimeConfig.leaseTermSeconds(),
                reuseDelayMillis);

        /*
         * Anchor the local deadline before entering MySQL. The database starts
         * its lease during this call, so measuring after the response would add
         * network/commit latency to the local ownership window and could let the
         * old node generate after MySQL has made the worker reusable elsewhere.
         * A slow acquire therefore consumes this safe term instead of extending
         * it. If the call consumes the whole term, generator.start() fails closed.
         */
        long acquireStartedAtNanos = System.nanoTime();
        Optional<LeaseHandle> leaseHandle = leaseManager.tryAcquire(request);
        if (leaseHandle.isEmpty()) {
            throw IdGenerationException.workerCandidateUnavailable(
                    properties.generatorId(), properties.workerId());
        }
        return new RuntimeCreateContext(new MysqlRuntimeAttachment(
                leaseHandle.get(),
                new AtomicBoolean(false),
                acquireStartedAtNanos + localSafeTermNanos));
    }

    /**
     * Durably registers one worker after the existing candidate scan is exhausted.
     * The registrar completes its transaction before the base workflow acquires
     * the worker lease.
     */
    @Override
    protected Optional<GeneratorProperties> registerGeneratorProperties(long generatorId) {
        // The compatibility constructor preserves the previous non-registering behavior.
        if (workerRegistrar == NO_DYNAMIC_REGISTRATION) {
            return Optional.empty();
        }
        return Optional.of(workerRegistrar.registerNext(generatorId));
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
            /*
             * RuntimeManagerClosed is a normal shutdown race. Other
             * IdGenerationException values are still controlled runtime states,
             * so this scheduled tick can be skipped without killing the JVM.
             */
            LOGGER.log(Level.WARNING, "skip mysql lease renew tick: " + e.getMessage(), e);
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
            renewWorkerExecutor.submit(() -> renewRuntimeEntry(entry));
        } catch (RejectedExecutionException e) {
            attachment.renewInFlight().set(false);
            LOGGER.log(Level.WARNING, "mysql lease renew task rejected: "
                    + runtimeEntryDescription(entry), e);
        } catch (RuntimeException e) {
            attachment.renewInFlight().set(false);
            LOGGER.log(Level.SEVERE, "unexpected mysql lease renew submit failure: "
                    + runtimeEntryDescription(entry), e);
            // rethrow unexpected RuntimeException to shut down the manager
            throw e;
        }
    }

    private void renewRuntimeEntry(RuntimeGeneratorEntry entry) {
        MysqlRuntimeAttachment attachment = mysqlAttachment(entry.attachment());
        try {
            if (!(entry.generator() instanceof LeasedIdGenerator generator)) {
                LOGGER.log(Level.WARNING, "mysql runtime entry is not a leased generator, stopping it: "
                        + runtimeEntryDescription(entry));
                stopGeneratorInstance(entry);
                return;
            }
            if (!generator.canGenerate()) {
                LOGGER.log(Level.WARNING, "mysql leased generator cannot generate before renew, stopping it: "
                        + runtimeEntryDescription(entry));
                stopGeneratorInstance(entry);
                return;
            }

            boolean renewed;
            /*
             * As with initial acquisition, the safe deadline is based on when
             * renewal began, not when its response arrived. Otherwise a slow DB
             * response would silently consume the configured DB/local safety
             * gap and could extend local generation beyond expired_at.
             */
            long renewStartedAtNanos = System.nanoTime();
            try {
                renewed = leaseManager.renew(attachment.leaseHandle());
            } catch (SnowflakeException e) {
                if (!isLeaseOperationFailure(e)) {
                    throw e;
                }
                /*
                 * A transient DB failure does not immediately prove stale
                 * ownership. Do not extend the local deadline; the cleaner will
                 * stop generation if renewals keep failing.
                 */
                LOGGER.log(Level.WARNING, "mysql lease renew failed, local deadline was not extended, entry: "
                        + runtimeEntryDescription(entry)
                        + ", lease: " + leaseHandleDescription(attachment.leaseHandle()), e);
                return;
            }
            if (!renewed) {
                // affectedRows == 0 means the lease handle is stale or ownership was lost.
                LOGGER.log(Level.WARNING, "mysql lease renew returned false, stopping stale entry: "
                        + runtimeEntryDescription(entry)
                        + ", lease: " + leaseHandleDescription(attachment.leaseHandle()));
                stopGeneratorInstance(entry);
                return;
            }

            try {
                generator.renewLocalDeadline(renewStartedAtNanos + localSafeTermNanos);
            } catch (SnowflakeException e) {
                LOGGER.log(Level.WARNING, "mysql lease renewed in DB but local deadline update failed, stopping entry: "
                        + runtimeEntryDescription(entry)
                        + ", lease: " + leaseHandleDescription(attachment.leaseHandle()), e);
                stopGeneratorInstance(entry);
            }
        } catch (SnowflakeException e) {
            if (e.getErrorCode() == SnowflakeErrorCode.LeaseExpired) {
                stopGeneratorInstance(entry);
            }
            LOGGER.log(Level.WARNING, "mysql lease renew task skipped by controlled runtime state, entry: "
                    + runtimeEntryDescription(entry), e);
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
        } catch (SnowflakeException e) {
            LOGGER.log(Level.WARNING, "skip mysql lease cleanup tick: " + e.getMessage(), e);
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
                    LOGGER.log(Level.WARNING, "mysql lease renew task discarded because renew executor is saturated");
                    /*
                     * Throw after marking backpressure so submitRenewTask can
                     * clear the per-entry renewInFlight guard. Silent discard
                     * would leave that entry unable to renew again.
                     */
                    throw new RejectedExecutionException("mysql lease renew executor is saturated");
                }) {
            @Override
            protected void afterExecute(Runnable runnable, Throwable throwable) {
                super.afterExecute(runnable, throwable);
                handleUnexpectedBackgroundFailure(
                        "mysql lease renew worker",
                        extractTaskFailure(runnable, throwable));
            }
        };
    }

    private ScheduledExecutorService newBackgroundScheduler(String threadNamePrefix) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                namedThreadFactory(threadNamePrefix)) {
            @Override
            protected void afterExecute(Runnable runnable, Throwable throwable) {
                super.afterExecute(runnable, throwable);
                handleUnexpectedBackgroundFailure(
                        threadNamePrefix,
                        extractTaskFailure(runnable, throwable));
            }
        };
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private void handleUnexpectedBackgroundFailure(String taskName, Throwable failure) {
        if (failure == null) {
            return;
        }
        if (shutdownFor.compareAndSet(null, failure)) {
            LOGGER.log(Level.SEVERE, taskName + " failed unexpectedly; shutting down mysql generator manager", failure);
            shutdown();
            return;
        }
        LOGGER.log(Level.WARNING, taskName + " failed after manager shutdown was already requested", failure);
    }

    private static Throwable extractTaskFailure(Runnable runnable, Throwable throwable) {
        if (throwable != null) {
            return throwable;
        }
        if (!(runnable instanceof Future<?> future) || !future.isDone()) {
            return null;
        }
        try {
            future.get();
            return null;
        } catch (CancellationException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static boolean isLeaseOperationFailure(SnowflakeException exception) {
        if (exception instanceof MysqlLeaseException) {
            return true;
        }
        return exception.getErrorCode() == SnowflakeErrorCode.MysqlLeaseOperationFailed
                || exception.getErrorCode() == SnowflakeErrorCode.MysqlLeaseOperationTimeout;
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

    private static String runtimeEntryDescription(RuntimeGeneratorEntry entry) {
        return "generatorId=" + entry.generatorId()
                + ", generatorName=" + entry.generatorName()
                + ", workerId=" + entry.workerId()
                + ", instanceId=" + entry.instanceId();
    }

    private static String leaseHandleDescription(LeaseHandle leaseHandle) {
        return "leaseDomain=" + leaseHandle.leaseDomain()
                + ", leaseRef=" + leaseHandle.leaseRef()
                + ", ownerId=" + leaseHandle.ownerId()
                + ", fencingToken=" + leaseHandle.fencingToken();
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
            long initialLocalExpireAtNanos) {
    }
}
