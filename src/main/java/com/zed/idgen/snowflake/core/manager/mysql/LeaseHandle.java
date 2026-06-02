package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Ownership proof returned by a successful lease acquire.
 *
 * Later renew/release operations must use the same owner token and fencing
 * token. affectedRows == 0 then means the handle is stale or the lease is gone.
 */
public record LeaseHandle(
        String leaseDomain,
        long leaseRef,
        String ownerId,
        String ownerToken,
        long fencingToken,
        int leaseTermSeconds) {

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
    }
}
