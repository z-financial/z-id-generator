# MySQL Lease Design

## Purpose

The lease system gives one runtime exclusive, time-bounded ownership of a
Snowflake worker ID. It prevents simultaneous use of the same worker and delays
reuse long enough that a new generator cannot restart its sequence in a time
bucket still reachable by the previous generator.

## Responsibilities

### `LeaseManager`

`LeaseManager` is the semantic boundary. It defines acquisition, renewal,
release, stale-handle rejection, and the post-expiration cooling requirement.

- `tryAcquire` returns an `Optional`: empty means normal contention or cooling;
  database failures remain exceptions.
- `renew` succeeds only for the active ownership identity.
- `release` expires ownership but retains the row and its fencing history.

`MysqlLeaseManager` implements this contract with a transaction and row lock
during acquisition. Renewal and release use guarded atomic updates.

### `LeaseEntity`

`LeaseEntity` is only the persistence representation of the `leases` table. It
does not decide lease behavior. `LeaseDao` owns SQL mutations, while
`LeaseManager` owns their meaning.

The resource key is `(lease_domain, lease_ref)` and must remain unique.

## Ownership identity

| Field | Meaning | Why it exists |
| --- | --- | --- |
| `owner_id` | Stable service-instance identity | Diagnostics and operational visibility; it may be reused after restart. |
| `owner_token` | Unique runtime ownership session | Distinguishes processes and restarts sharing the same owner ID. |
| `fencing_token` | Monotonic lease generation | Orders takeovers and deterministically rejects stale handles or downstream operations. |

The owner token answers **which runtime session?** The fencing token answers
**which ownership generation is newer?** A unique owner token is enough for
equality-based lease-table updates, but it cannot order two owners. Keeping the
fencing token makes the lease usable as a general stale-owner guard.

The fencing token is not compulsory for the current ID generator's uniqueness
guarantee. That guarantee comes from exclusive worker ownership, the local safe
deadline, and the post-expiration reuse delay. Fencing remains meaningful for a
future general distributed-lease design, where a database, job runner, or other
protected resource may need to compare generations and reject a delayed old
owner after a newer owner has taken over.

Renew and release match the resource key plus all ownership fields. A mismatch
returns `false`; it must not mutate the current lease.

## Time states

A lease moves through three database-time states:

1. **Active:** `now < expired_at`; only the current owner may renew or release.
2. **Cooling:** `expired_at <= now < reusable_at`; the old owner is invalid, but
   no new generator may reset the worker sequence yet.
3. **Reusable:** `reusable_at <= now`; a takeover may advance the fencing token
   and install a new owner token.

`reusable_at` is calculated as:

```text
expired_at + generator time unit + clock-skew window + safety margin
```

All lease decisions use MySQL `UTC_TIMESTAMP(3)`. Application monotonic time is
used only to stop local generation before the database lease may become unsafe.

## Persistence invariants

- `reusable_at > expired_at`.
- A takeover increments `fencing_token` atomically.
- Renew cannot revive an expired lease.
- Release does not delete the row; it starts the cooling period immediately.
- A worker is taken over only after `reusable_at`.
- Existing rows must not be deleted or recreated during normal operation,
  because doing so would reset fencing history and bypass the cooling barrier.
