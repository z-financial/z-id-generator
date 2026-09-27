package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Runtime lease/renewal settings for MysqlGeneratorRuntimeManager.
 * <p>
 * localSafeTermMillis must be shorter than the DB lease term. The local
 * LeasedIdGenerator then stops before MySQL can consider the worker lease
 * expired, leaving room for network latency and scheduling delay.
 */
public record MysqlGeneratorRuntimeConfig(
        int leaseTermSeconds,
        long localSafeTermMillis,
        long clockSkewWindowMillis,
        long reuseSafetyMarginMillis,
        long renewIntervalMillis,
        long cleanerIntervalMillis,
        int renewCorePoolSize,
        int renewMaxPoolSize,
        int renewQueueSize) {

    private static final int DEFAULT_LEASE_TERM_SECONDS = 10;
    private static final long DEFAULT_LOCAL_SAFE_TERM_MILLIS = 8_000L;
    private static final long DEFAULT_CLOCK_SKEW_WINDOW_MILLIS = 1_000L;
    private static final long DEFAULT_REUSE_SAFETY_MARGIN_MILLIS = 100L;
    private static final long MAX_CLOCK_SKEW_WINDOW_MILLIS = 86_400_000L;
    private static final long MAX_REUSE_SAFETY_MARGIN_MILLIS = 86_400_000L;
    private static final long DEFAULT_RENEW_INTERVAL_MILLIS = 5_000L;
    private static final long DEFAULT_CLEANER_INTERVAL_MILLIS = 1_000L;
    private static final int DEFAULT_RENEW_CORE_POOL_SIZE = 2;
    private static final int DEFAULT_RENEW_MAX_POOL_SIZE = 4;
    private static final int DEFAULT_RENEW_QUEUE_SIZE = 1024;

    public MysqlGeneratorRuntimeConfig {
        if (leaseTermSeconds <= 0) {
            throw new InvalidConfigException("mysql runtime lease term seconds must be positive");
        }
        if (localSafeTermMillis <= 0) {
            throw new InvalidConfigException("mysql runtime local safe term millis must be positive");
        }
        if (localSafeTermMillis >= leaseTermSeconds * 1000L) {
            throw new InvalidConfigException("mysql runtime local safe term must be shorter than DB lease term");
        }
        if (clockSkewWindowMillis < 0) {
            throw new InvalidConfigException("mysql runtime clock skew window must not be negative");
        }
        if (clockSkewWindowMillis > MAX_CLOCK_SKEW_WINDOW_MILLIS) {
            throw new InvalidConfigException("mysql runtime clock skew window must not exceed one day");
        }
        if (reuseSafetyMarginMillis < 0) {
            throw new InvalidConfigException("mysql runtime reuse safety margin must not be negative");
        }
        if (reuseSafetyMarginMillis > MAX_REUSE_SAFETY_MARGIN_MILLIS) {
            throw new InvalidConfigException("mysql runtime reuse safety margin must not exceed one day");
        }
        if (renewIntervalMillis <= 0) {
            throw new InvalidConfigException("mysql runtime renew interval millis must be positive");
        }
        if (renewIntervalMillis >= localSafeTermMillis) {
            throw new InvalidConfigException("mysql runtime renew interval must be shorter than local safe term");
        }
        if (cleanerIntervalMillis <= 0) {
            throw new InvalidConfigException("mysql runtime cleaner interval millis must be positive");
        }
        if (renewCorePoolSize <= 0) {
            throw new InvalidConfigException("mysql runtime renew core pool size must be positive");
        }
        if (renewMaxPoolSize < renewCorePoolSize) {
            throw new InvalidConfigException("mysql runtime renew max pool size must be >= core pool size");
        }
        if (renewQueueSize <= 0) {
            throw new InvalidConfigException("mysql runtime renew queue size must be positive");
        }
    }

    public static MysqlGeneratorRuntimeConfig defaults() {
        return new MysqlGeneratorRuntimeConfig(
                DEFAULT_LEASE_TERM_SECONDS,
                DEFAULT_LOCAL_SAFE_TERM_MILLIS,
                DEFAULT_CLOCK_SKEW_WINDOW_MILLIS,
                DEFAULT_REUSE_SAFETY_MARGIN_MILLIS,
                DEFAULT_RENEW_INTERVAL_MILLIS,
                DEFAULT_CLEANER_INTERVAL_MILLIS,
                DEFAULT_RENEW_CORE_POOL_SIZE,
                DEFAULT_RENEW_MAX_POOL_SIZE,
                DEFAULT_RENEW_QUEUE_SIZE);
    }

    /**
     * Returns the minimum cooling period for a worker after its lease expires.
     *
     * <p>The time-unit component forces a newly created Snowflake provider into
     * a later timestamp bucket before its sequence restarts at zero. The skew
     * window must be configured as the maximum relative clock difference
     * between any two application nodes (not the deviation of one node from
     * UTC). The final margin absorbs timestamp precision and scheduling noise.
     */
    public long reuseDelayMillis(TimeUnit timeUnit) {
        if (timeUnit == null) {
            throw new InvalidConfigException("mysql runtime domain time unit must not be null");
        }
        long timeUnitMillis = switch (timeUnit) {
            case MILLISECOND -> 1L;
            case SECOND -> 1_000L;
        };
        return timeUnitMillis + clockSkewWindowMillis + reuseSafetyMarginMillis;
    }
}
