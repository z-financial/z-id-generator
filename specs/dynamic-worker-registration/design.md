# Dynamic Worker Registration and Lease Acquisition — Design

Status: Proposed  
Source of truth: [SPEC](./spec.md)  
Delivery tracking: [tasks](./task.md)

## 1. Purpose

Allow a configured MySQL generator to start without a pre-seeded worker row. Automatic
startup first competes for eligible retained workers and allocates a new domain-scoped
worker ID only after those candidates are exhausted.

Registration and leasing remain separate transactions. The worker and counter commit
before the lease API, generator factory, `start()`, callbacks, or runtime publication
are invoked. A committed worker without a lease is therefore valid recoverable state.

Domain and generator provisioning is described by the sibling
[Application Registration Administration design](../application-registration-admin/design.md).

## 2. Design boundaries

### Goals

- Reuse eligible retained workers before allocating capacity.
- Allocate worker IDs atomically within a domain.
- Serialize same-domain allocation without globally serializing all domains.
- Close the worker transaction before lease and application/runtime work.
- Preserve exact-worker startup and all non-MySQL behavior.
- Expose distinct signals for configuration, capacity, registration, and lease failure.

### Non-goals

- changing the public `GeneratorRuntimeManager` interface;
- changing lease acquisition, fencing, renewal, release, or cooling semantics;
- deleting or recycling worker or lease rows;
- runtime creation or repair of a missing counter;
- a configurable limit in addition to the `workerBit` range;
- general deadlock retry or a scan/allocation rescan loop; and
- dynamic registration for local-file or configured/in-memory managers.

## 3. Runtime flow

Automatic startup for `startGeneratorInstance(generatorId)` becomes:

```text
load configured generator and eligible retained workers
for each worker ordered by worker_id:
  skip if already running in this manager
  try existing beforeCreate -> factory -> start -> publish path
  if lease/local contention is skippable, continue
  otherwise fail

ask the source-specific extension point for one new candidate
if no candidate is supported, fail with no available worker

register worker in a short transaction
commit and close the registration session
run the same beforeCreate -> factory -> start -> publish path
```

The new candidate is attempted once. This phase deliberately does not rescan workers
or allocate repeatedly after lease contention.

`startGeneratorInstance(generatorId, workerId)` remains lookup-only. It searches the
registered candidates for the requested worker and never invokes the registration
extension point or advances a counter.

## 4. Current integration points

The design extends the current code rather than creating a second runtime pipeline:

| Existing component | Change |
| --- | --- |
| `AbstractGeneratorRuntimeManager` | Accept an empty candidate list for automatic start and add one protected post-scan registration hook. |
| `MysqlGeneratorRuntimeManager` | Inject the MySQL registration collaborator and implement the hook. |
| `MysqlRegistrationLoader` | Treat zero workers as an empty immutable list rather than invalid configuration. |
| `WorkerDao` | Add ordered candidate discovery and worker insertion. |
| `MysqlDaoFactory` / `MysqlDaoSession` | Register and expose `CounterDao`. |
| `MysqlLeaseManager` | No semantic change; remains the acquisition authority. |

The base hook should return at most one committed `GeneratorProperties` candidate and
default to no candidate. Configured and local-file loaders retain their current
validation, so allowing an empty list in the base manager only enables the MySQL path.

The test-oriented `MysqlGeneratorRuntimeManager` constructor accepts an injectable
registration collaborator, just as it currently accepts a `RegistrationLoader` and
`LeaseManager`. Unit tests can therefore validate orchestration without MySQL.

## 5. Counter model

Use a general-purpose counter table:

```sql
CREATE TABLE IF NOT EXISTS counters (
    counter_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    counter_ref VARCHAR(191)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL,
    counter_name VARCHAR(128) NOT NULL,
    next_value BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_counter_ref (counter_ref)
);
```

`counter_ref` is the immutable machine identity. `counter_name` is descriptive and is
not unique. The canonical reference for domain worker allocation is:

```text
worker-id:domain:{domain_id}
```

The administration flow creates this row with `next_value = 0` in the same transaction
as a new domain. Runtime registration never inserts or repairs it.

No database `CHECK` constraint is added for the maximum worker value because the limit
comes from `IdCodec.getMaxWorker()` for the persisted domain definition.

## 6. Existing-worker discovery

`WorkerDao` exposes a non-locking candidate query for one domain and generator. It
returns workers ordered by `worker_id ASC` whose lease row is either absent or has
`reusable_at <= UTC_TIMESTAMP(3)`. Using database time matches the existing lease DAO.

Discovery is advisory, not authoritative:

- A worker with no lease may be claimed concurrently. The unique
  `(lease_domain, lease_ref)` key decides the insert winner.
- A reusable worker may be taken concurrently. The existing conditional takeover
  rechecks both `reusable_at` and the observed fencing token.
- A duplicate insert or zero-row takeover is a normal candidate miss.

The query does not use `FOR UPDATE` and holds no lock while `beforeCreate` calls the
lease manager. `MysqlLeaseManager` remains the final concurrency authority.

The existing `WorkerDao.findByGenerator` query must at minimum gain deterministic
`ORDER BY worker_id`; a dedicated eligible-candidate query is preferred so currently
leased and cooling workers are not attempted unnecessarily.

## 7. Worker registration collaborator

Add a MySQL-only collaborator with one logical operation:

```java
GeneratorProperties registerNext(long generatorId);
```

The exact name is implementation-level, but its contract is fixed:

- open a new non-auto-commit `MysqlDaoSession` for each call;
- load the generator and its domain definition in that session;
- lock only the exact canonical counter row;
- validate domain ownership and encoded worker capacity;
- insert one worker and conditionally advance the observed counter;
- commit and close before returning; and
- return immutable `GeneratorProperties` for the committed worker.

The collaborator stores only immutable DAO/configuration references. It never caches a
session, mapper, connection, transaction, or mutable allocation state.

## 8. Registration transaction

```text
begin
  load generator_config by generator_id
  require configured domain_id
  load domain definition
  select counter by "worker-id:domain:{domain_id}" for update
  require counter exists
  require next_value <= new IdCodec(domain).getMaxWorker()
  insert workers(
      domain_id,
      generator_id,
      worker_id = next_value)
  update counters
     set next_value = next_value + 1
   where counter_id = observed_counter_id
     and next_value = observed_next_value
  require exactly one updated row
commit
close session
return GeneratorProperties(domain, generator, allocatedWorkerId, null)
```

The worker insert and increment commit or roll back together. A failed insert or
conditional update consumes no finite capacity.

`worker_id` remains distinct from the `workers.id` surrogate key. Existing
`UNIQUE(domain_id, worker_id)` remains the domain-level safety invariant, while
`UNIQUE(generator_id, worker_id)` remains defensive.

Although the counter lock already serializes conforming writers, the conditional
increment detects stale observations and unexpected non-conforming updates. Any row
count other than one is a registration transaction failure.

## 9. Commit-before-lease boundary

The registration transaction contains only configuration reads, the counter lock,
capacity validation, worker insertion, and counter advancement. The following always
happen after commit and session close:

- `LeaseManager.tryAcquire`;
- generator construction;
- generator `start()`;
- runtime publication;
- application callbacks or hooks; and
- external logging or metrics exporters.

The effective lock order is:

```text
new worker: counter row -> worker insert -> commit -> lease insert/takeover
reuse:       lease insert/takeover only
renew:       lease update only
release:     lease update only
```

No code path may lock a worker or lease row and then request the counter lock.

## 10. Runtime lifecycle details

### Empty first start

For a valid generator with no worker rows, `MysqlRegistrationLoader` returns
`List.of()`. The automatic base-manager start loop completes without error and reaches
the registration hook. A successful first allocation returns worker `0` when the
counter starts at zero.

### Existing candidate wins

When an existing candidate acquires a lease and is published, the registration hook is
not called and the counter is unchanged.

### Existing candidates lose

Lease contention maps to the existing skippable `WorkerCandidateUnavailable` signal.
The base manager tries the next candidate. After the last miss, MySQL registers at
most one new worker.

### Failure after worker commit

If lease acquisition fails or loses contention after registration, the committed
worker remains discoverable. It is not deleted and the counter is not decremented.

If generator construction, `start()`, attachment, or publication fails after lease
acquisition, the existing `onCreateFailure` path releases the lease best-effort. If
release fails, expiry and `reusable_at` preserve safety.

### Local concurrency

The existing fast local-map check avoids unnecessary work, and the final publication
check under `dataWriteLock` remains authoritative for threads in one manager. MySQL
lease acquisition remains authoritative across managers and processes.

## 11. DAO and entity changes

### Counter persistence

- Add `CounterEntity` mapped to `counters` with auto-generated `counter_id`.
- Add `CounterDao.findByRefForUpdate(String)` using an exact indexed lookup.
- Add `CounterDao.advance(long counterId, long observedNextValue)` using the conditional
  update and returning the affected row count.
- Register `CounterDao` in `MysqlDaoFactory` and expose it from `MysqlDaoSession`.

The administration design also requires counter insert and non-locking exact lookup.

### Worker persistence

- Use `BaseMapper.insert` or an explicit method to insert `WorkerEntity`.
- Add deterministic candidate discovery ordered by `worker_id`.
- Keep discovery non-locking and lease arbitration in `MysqlLeaseManager`.

### Registration loading

- Keep generator/domain validation already performed by `MysqlRegistrationLoader`.
- Return an empty immutable list when the generator is valid but has no workers.
- Preserve validation of every returned worker and duplicate-worker detection.

## 12. Failure and signal mapping

| Condition | Required behavior |
| --- | --- |
| Counter missing | Fail as a distinct configuration/integrity error; create nothing. |
| Generator/domain mismatch | Fail closed before worker insertion. |
| `next_value` above `getMaxWorker()` | Report capacity exhaustion; leave counter unchanged. |
| Worker insert failure | Roll back worker and counter transaction. |
| Conditional increment affects zero rows | Roll back and report registration transaction failure. |
| Counter/registration timeout | Translate to a registration timeout/failure signal. |
| Lease insert/takeover contention | Treat as a normal candidate miss. |
| Lease database timeout/failure | Preserve existing lease exception semantics. |

Raw `SQLException` and MyBatis `PersistenceException` do not escape public runtime
operations. New `SnowflakeErrorCode` values should be limited to distinctions callers
can act on: missing counter, capacity exhausted, and registration failure/timeout.

## 13. Schema, deployment, and permissions

Add `counters` to `src/main/resources/mysql/lease.sql` for clean installations.
Deploy the schema before enabling either administrative provisioning or dynamic worker
registration.

The runtime principal requires:

```text
domains:           SELECT
generator_configs: SELECT
counters:          SELECT, UPDATE
workers:           SELECT, INSERT
leases:            SELECT, INSERT, UPDATE
```

It also needs the deployed MySQL privilege required for `SELECT ... FOR UPDATE`. It
does not need DDL or delete permissions.

Existing populated deployments need a separately approved backfill/rollout design.
That design must initialize each domain counter above its highest retained worker ID
while writers are stopped or otherwise coordinated. This feature does not add a
migration implicitly because the SPEC covers clean-install schema and explicitly
forbids runtime counter repair.

## 14. Verification strategy

### Unit tests

- base-manager automatic start reaches the hook after an empty candidate list;
- a successful existing candidate avoids registration;
- exhausted/skipped candidates invoke registration once;
- exact-worker start never registers;
- the default base hook preserves non-MySQL behavior;
- the MySQL collaborator is injectable; and
- loader results are immutable and ordered.

### DAO and transaction tests

- exact counter lookup locks the intended row;
- conditional increment requires the observed value;
- insert or increment failure rolls back both worker and counter state;
- missing counter and capacity exhaustion leave no partial state; and
- each concurrent call owns a distinct session.

### Real-MySQL lifecycle tests

- first start commits worker `0`, advances the counter to `1`, then leases and publishes;
- same-domain concurrent registration returns distinct worker IDs;
- different-domain registration uses independent counter rows;
- concurrent claims of an unleased worker have one winner;
- concurrent claims of a reusable worker have one winner;
- lease failure leaves the committed worker claimable later;
- retained workers become reusable only after `reusable_at`;
- factory/start/publication events occur after registration commit and session close;
- non-MySQL manager tests retain their existing meaning; and
- the Maven build executes the pinned real-MySQL test harness.

## 15. Delivery order and open decisions

1. Add the clean-install counter schema, `CounterEntity`, and `CounterDao`.
2. Add worker insertion and stable eligible-candidate discovery.
3. Implement and test the transactional registration collaborator.
4. Add the protected base-manager hook and MySQL injection path.
5. Allow empty MySQL worker lists and route the committed candidate through the
   existing lease/create/start/publish pipeline.
6. Add unit and real-MySQL concurrency, rollback, ordering, and recovery coverage.

Before implementation is complete, choose stable project error codes and pin the
test-only MySQL container dependency and lifecycle. A production backfill migration,
if required, remains a separate deployment decision.
