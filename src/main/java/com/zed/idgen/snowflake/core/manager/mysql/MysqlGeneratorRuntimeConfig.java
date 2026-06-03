package com.zed.idgen.snowflake.core.manager.mysql;

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
        long renewIntervalMillis,
        long cleanerIntervalMillis,
        int renewCorePoolSize,
        int renewMaxPoolSize,
        int renewQueueSize) {

    private static final int DEFAULT_LEASE_TERM_SECONDS = 10;
    private static final long DEFAULT_LOCAL_SAFE_TERM_MILLIS = 8_000L;
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
                DEFAULT_RENEW_INTERVAL_MILLIS,
                DEFAULT_CLEANER_INTERVAL_MILLIS,
                DEFAULT_RENEW_CORE_POOL_SIZE,
                DEFAULT_RENEW_MAX_POOL_SIZE,
                DEFAULT_RENEW_QUEUE_SIZE);
    }
}
