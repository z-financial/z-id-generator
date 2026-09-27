package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Ownership handle returned by {@link LeaseManager#tryAcquire(LeaseRequest)}.
 * See {@link LeaseManager} for the ownership and fencing contract.
 */
public record LeaseHandle(
        String leaseDomain,
        long leaseRef,
        String ownerId,
        String ownerToken,
        long fencingToken,
        int leaseTermSeconds,
        long reuseDelayMillis) {

    public LeaseHandle {
        if (leaseDomain == null || leaseDomain.isBlank()) {
            throw new InvalidConfigException("lease domain must not be blank");
        }
        if (leaseRef < 0) {
            throw new InvalidConfigException("lease ref must not be negative");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new InvalidConfigException("lease owner id must not be blank");
        }
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new InvalidConfigException("lease owner token must not be blank");
        }
        if (fencingToken < 0) {
            throw new InvalidConfigException("lease fencing token must not be negative");
        }
        if (leaseTermSeconds <= 0) {
            throw new InvalidConfigException("lease term seconds must be positive");
        }
        if (reuseDelayMillis <= 0) {
            throw new InvalidConfigException("lease reuse delay millis must be positive");
        }
    }
}
