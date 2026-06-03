package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lease-aware wrapper for one runtime generator instance.
 * The database lease manager remains the source of truth for acquire/renew; this
 * class only enforces a conservative local monotonic deadline before returning
 * IDs, so IDs are not returned after the local lease guard has expired.
 */
public class LeasedIdGenerator implements IdGeneratorInstance {
    private final IdGeneratorInstance delegate;
    @Getter
    private final LeaseInfo leaseInfo;
    /*
     * Local monotonic safe deadline based on System.nanoTime(), not wall-clock
     * time. It should be earlier than the DB lease expiry so generation stops
     * locally before the lease can expire in MySQL.
     */
    private final AtomicLong localExpireAtNanos;
    /*
     * Terminal local lease state. Once a generation/start/renew path commits
     * lease loss, the same runtime generator must never be revived by a later
     * renew callback.
     */
    private final AtomicBoolean leaseLost = new AtomicBoolean(false);
    /*
     * Serializes rare lifecycle operations and the rare expired-deadline path.
     * Hot getUid() calls only take this lock when the fast deadline check fails.
     */
    private final Object lifecycleLock = new Object();

    public LeasedIdGenerator(IdGeneratorInstance delegate, long localExpireAtNanos, LeaseInfo leaseInfo) {
        if (delegate == null) {
            throw new InvalidConfigException("delegate generator must not be null");
        }
        if (leaseInfo == null) {
            throw new InvalidConfigException("lease info must not be null");
        }
        if (localExpireAtNanos <= 0) {
            throw new InvalidConfigException("local expire deadline must be positive");
        }
        this.delegate = delegate;
        this.leaseInfo = leaseInfo;
        this.localExpireAtNanos = new AtomicLong(localExpireAtNanos);
    }

    @Override
    public boolean canGenerate() {
        synchronized (lifecycleLock) {
            /*
             * This method intentionally takes the lifecycle lock. A plain
             * deadline read could report "expired" while a concurrent renewal
             * is about to extend the local deadline. The lock gives callers a
             * coherent availability snapshot relative to start/stop/renew.
             */
            return !leaseLost.get() && !isLocalDeadlinePassed() && delegate.canGenerate();
        }
    }

    @Override
    public boolean start() {
        synchronized (lifecycleLock) {
            if (leaseLost.get() || isLocalDeadlinePassed()) {
                markLeaseLost();
                throw IdGenerationException.leaseExpired();
            }
            return delegate.start();
        }
    }

    @Override
    public boolean stop() {
        synchronized (lifecycleLock) {
            return delegate.stop();
        }
    }

    @Override
    public long getInstanceId() {
        return delegate.getInstanceId();
    }

    @Override
    public long getUid() {
        /*
         * First guard: fail before touching the local Snowflake provider if the
         * lease is already known lost or the local safe deadline has passed.
         * Normal overhead is one AtomicBoolean read, one nanoTime call, and one
         * AtomicLong read; the lock is only touched on the rare expired path.
         */
        ensureAvailableForGenerate();

        /*
         * Delegate remains responsible for local generator lifecycle and
         * Snowflake correctness: status, clock rollback, sequence overflow, and
         * provider synchronization still live below this wrapper.
         */
        long uid = delegate.getUid();

        /*
         * Second guard: protects against long latency between the first guard
         * and provider return, such as GC pause or slow batch/provider work. It
         * discards generated IDs instead of returning after local lease expiry.
         *
         * This does not prove time cannot pass immediately after this check. The
         * real safety margin comes from making localExpireAtNanos earlier than the
         * DB lease expiry. The second check only closes the large obvious window.
         */
        ensureAvailableForGenerate();
        return uid;
    }

    @Override
    public long[] getUidByBatch(int size) {
        ensureAvailableForGenerate();
        long[] ids = delegate.getUidByBatch(size);
        ensureAvailableForGenerate();
        return ids;
    }

    /**
     * Called after DB renew succeeds. The new deadline must be a local
     * System.nanoTime()-based safe deadline, normally earlier than the DB
     * expired_at value to leave room for latency and scheduling pauses.
     */
    public void renewLocalDeadline(long newSafeDeadlineNanos) {
        synchronized (lifecycleLock) {
            if (leaseLost.get()) {
                throw IdGenerationException.leaseExpired();
            }

            long now = System.nanoTime();
            long currentDeadline = localExpireAtNanos.get();
            if (now >= currentDeadline || now >= newSafeDeadlineNanos) {
                markLeaseLost();
                throw IdGenerationException.leaseExpired();
            }

            /*
             * Avoid shortening the local safe deadline if two renew callbacks
             * complete out of order. Renewal is infrequent, so using this small
             * monitor is clearer than a lock-free protocol around terminal state.
             */
            localExpireAtNanos.set(Math.max(currentDeadline, newSafeDeadlineNanos));
        }
    }

    /**
     * Stops this generator when its local safe lease deadline has been lost.
     * Background cleaners use this before removing the runtime entry. Returning
     * true means the caller should remove the generator from the runtime map.
     */
    public boolean stopIfLocalExpired() {
        synchronized (lifecycleLock) {
            if (isLocalExpired()) {
                markLeaseLost();
                return true;
            }
            return false;
        }
    }

    private void ensureAvailableForGenerate() {
        if (leaseLost.get()) {
            throw IdGenerationException.leaseExpired();
        }
        if (System.nanoTime() < localExpireAtNanos.get()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (leaseLost.get()) {
                throw IdGenerationException.leaseExpired();
            }
            if (System.nanoTime() >= localExpireAtNanos.get()) {
                markLeaseLost();
                throw IdGenerationException.leaseExpired();
            }
        }
    }

    private void markLeaseLost() {
        leaseLost.set(true);
        delegate.stop();
    }

    private boolean isLocalExpired() {
        return leaseLost.get() || isLocalDeadlinePassed();
    }

    private boolean isLocalDeadlinePassed() {
        return System.nanoTime() >= localExpireAtNanos.get();
    }
}
