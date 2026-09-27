package com.zed.idgen.snowflake.core.manager.mysql;

import java.util.Optional;

/**
 * Contract for exclusive, time-bounded ownership of a lease resource.
 *
 * <p>A resource is identified by {@code (leaseDomain, leaseRef)}. Ownership is
 * described by three deliberately different values:
 *
 * <ul>
 *   <li>{@code ownerId}: a stable, observable application-instance identity
 *       used for diagnostics; it is not sufficient proof of ownership because
 *       an instance id may be reused after restart.</li>
 *   <li>{@code ownerToken}: a unique token for one runtime ownership session.
 *       It distinguishes different processes or restarts that share an owner
 *       id and is required when renewing or releasing a lease.</li>
 *   <li>{@code fencingToken}: a database-controlled, monotonically increasing
 *       lease generation. Every takeover advances it, allowing stale handles
 *       and, when propagated, stale operations on protected resources to be
 *       rejected deterministically.</li>
 * </ul>
 *
 * <p>The owner token proves which session is acting; the fencing token proves
 * which lease generation is newer. Fencing is not required for Snowflake ID
 * uniqueness itself; local expiration and the worker reuse cooling period
 * provide that protection. It is retained to support a stronger, reusable
 * distributed-lease contract in which downstream resources can reject stale
 * owners. Implementations must not reset the fencing sequence by deleting a
 * released lease row. A released or naturally expired resource remains
 * unavailable until its cooling period has elapsed.
 */
public interface LeaseManager {

    /**
     * Attempts to acquire a resource without treating contention as an error.
     *
     * @return the ownership handle when acquired, or empty while another owner
     *         holds the resource or its post-expiration cooling period is active
     */
    Optional<LeaseHandle> tryAcquire(LeaseRequest request);

    /**
     * Extends an active lease when the complete ownership identity still
     * matches; returns false for an expired or stale handle.
     */
    boolean renew(LeaseHandle handle);

    /**
     * Relinquishes ownership without deleting the durable lease identity.
     * Implementations must preserve a cooling interval before another owner can
     * reuse the resource; returning {@code true} means that transition was
     * persisted for the supplied ownership identity.
     */
    boolean release(LeaseHandle handle);
}
