package com.zed.idgen.snowflake.core.component.provider.impl;

import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.exception.ClockRollbackException;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.component.provider.IdProvider;
import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.strategy.StrategyExecutor;

/**
 * Stateful Snowflake ID provider.
 *
 * <p>The public generation methods synchronize on this provider instance to
 * protect the current time slice, sequence cursor, and reusable single-ID
 * buffer as one critical section. Method-level synchronization is intentional:
 * the protected scope is one complete generation operation, and keeping it
 * under one monitor makes the state transition easier to audit.</p>
 *
 * <p>Batch generation reserves a sequence range with one monitor acquisition
 * and one set of clock/policy checks per attempt, so its per-ID overhead is
 * normally lower than calling {@link #nextId()} repeatedly. The provider still
 * performs one ID encoding per result and holds the monitor while building the
 * batch, so very large batches can increase latency for competing callers.</p>
 */
public class DefaultIdProvider implements IdProvider {
    private final long worker;
    private final IdCodec idCodec;
    private final GeneratorPolicy policy;
    private long time;
    private long sequence;

    /*
     * Reused to avoid allocating an array on the single-ID hot path. The value
     * must be written and read while holding this provider's monitor. Moving the
     * lock only to doNextIds() would release it before nextId() reads element 0,
     * allowing another thread to overwrite the shared buffer first.
     */
    private final long[] singleId = new long[1];

    public DefaultIdProvider(long worker, IdCodec idCodec, GeneratorPolicy policy) {
        this.worker = worker;
        this.idCodec = idCodec;
        this.policy = policy;
    }

    /*
     * Called only while holding this provider's monitor.
     *
     * A batch is an all-or-nothing reservation within one time slice. If the
     * remaining sequence slots cannot contain the complete batch, this method
     * applies the configured exhaustion strategy instead of splitting the batch
     * across time slices. Consequently, the unused tail of the current slice may
     * be left unallocated. This deliberately favors simple batch semantics over
     * maximum utilization of every sequence slot.
     */
    private long[] doNextIds(int size, long[] arr) {
        if (size <= 0 || size > idCodec.getMaxSequence() + 1) {
            throw new IllegalArgumentException("id batch size exceeds max sequence: " + size);
        }

        for (int i = 0; i <= policy.maximumRetry(); i++) {
            long now = getCurrentTime();
            if (now > idCodec.getMaxTime()) {
                throw IdGenerationException.maxRetryExceeded();
            }

            if (now < time) {
                // throw exception directly on the last retry
                if (i != policy.maximumRetry() && StrategyExecutor.clockRollbackStrategy(policy.clockRollbackPolicy())) {
                    continue;
                } else {
                    throw new ClockRollbackException(String.format("Clock roll back, last time: %d, now: %d", time, now));
                }
            }

            if (time == now) {
                if (sequence + size - 1 > idCodec.getMaxSequence()) {
                    // throw exception directly on the last retry
                    if (i != policy.maximumRetry() && StrategyExecutor.idRunOutStrategy(policy.idRunOutPolicy())) {
                        continue;
                    } else {
                        throw IdGenerationException.idRunout();
                    }
                }
            } else {
                sequence = 0;
                time = now;
            }

            long oldSequence = sequence;
            sequence = sequence + size;
            long[] result = arr != null ? arr : new long[size];

            for (int s = 0; s < size; ++s) {
                result[s] = idCodec.buildId(time, worker, oldSequence + s);
            }
            return result;
        }
        throw new IllegalStateException("unexpected max retry exceeded");
    }

    private long getCurrentTime() {
        long now = System.currentTimeMillis();
        return switch (idCodec.getTimeUnit()) {
            case SECOND -> (now - idCodec.getEpochMs()) / 1000;
            case MILLISECOND -> now - idCodec.getEpochMs();
        };}

    @Override
    public synchronized long nextId() {
        long[] result = doNextIds(1, singleId);
        return result[0];
    }

    /**
     * Reserves and builds the whole batch under one monitor acquisition.
     */
    @Override
    public synchronized long[] nextIds(int size) {
        return doNextIds(size, null);
    }

}
