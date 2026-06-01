package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Immutable lease identity exposed by a leased generator.
 * This deliberately stays independent from MyBatis lease entities so core
 * generation code does not depend on MySQL persistence objects.
 */
public record LeaseInfo(
        String leaseDomain,
        long leaseRef,
        String ownerId,
        String ownerToken,
        long fencingToken) {

    public LeaseInfo {
        if (leaseDomain == null || leaseDomain.isBlank()) {
            throw new InvalidConfigException("lease domain must not be blank");
        }
        if (leaseRef < 0) {
            throw new InvalidConfigException("lease ref must not be negative");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new InvalidConfigException("owner id must not be blank");
        }
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new InvalidConfigException("owner token must not be blank");
        }
        if (fencingToken < 0) {
            throw new InvalidConfigException("fencing token must not be negative");
        }
    }
}
