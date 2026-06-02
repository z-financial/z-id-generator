package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

/**
 * Request to acquire one lease resource.
 *
 * fencingToken is optional and is only used for idempotent acquire checks when
 * the caller already has a known handle. A fresh acquire can pass null.
 */
public record LeaseRequest(
        String leaseDomain,
        long leaseRef,
        String leaseDescription,
        String ownerId,
        String ownerToken,
        Long fencingToken,
        int leaseTermSeconds) {

    public LeaseRequest {
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
        if (fencingToken != null && fencingToken < 0) {
            throw new InvalidConfigException("lease fencing token must not be negative");
        }
        if (leaseTermSeconds <= 0) {
            throw new InvalidConfigException("lease term seconds must be positive");
        }
    }

    public LeaseRequest(
            String leaseDomain,
            long leaseRef,
            String leaseDescription,
            String ownerId,
            String ownerToken,
            int leaseTermSeconds) {
        this(leaseDomain, leaseRef, leaseDescription, ownerId, ownerToken, null, leaseTermSeconds);
    }
}
