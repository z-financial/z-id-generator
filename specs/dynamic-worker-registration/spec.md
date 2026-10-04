# Dynamic Worker Registration and Lease Acquisition

Status: Proposed | Last updated: 2026-10-04

## 1. Context

MySQL runtime startup currently assumes at least one worker row is pre-seeded for a
generator. `MysqlRegistrationLoader` and the base runtime start path reject an empty
worker list, so a configured generator cannot start by allocating its first worker.
The schema also has no transactional counter for allocating finite domain-scoped
worker IDs.

MySQL deployments need runtime registration that first reuses an eligible retained
worker and allocates a new worker only when no existing candidate can be leased.
Registration and leasing must remain separate: counter locks must not span lease,
generator construction, application callbacks, or publication.

## 2. Decision and Scope

For `startGeneratorInstance(generatorId)`, the MySQL runtime shall try existing
registered workers in stable `worker_id` order. If none can be leased, it shall
allocate and persist the next domain worker ID in a short transaction, commit that
transaction, and then acquire the new worker's lease through the existing lease API.
Only a successfully leased worker may be constructed, started, and published locally.

A committed worker without a lease is valid recoverable state. Lease rows remain
retained permanently so fencing history and the post-expiration cooling period cannot
be bypassed.

### Included

- A general `counters` table and one canonical worker-ID counter per domain.
- Existing-worker discovery and lease competition.
- MySQL-only transactional worker registration and runtime integration.
- DAO, model, clean-install schema, error-signal, and MySQL integration-test support.
- A protected base-manager extension point that preserves non-MySQL behavior.

### Unchanged / out of scope

- The public `GeneratorRuntimeManager` interface and its start method signatures.
- Local-file and configured/in-memory registration behavior.
- Lease acquisition, renewal, release, fencing, and cooling-period semantics.
- Changes to `MysqlLeaseManager`, `LeaseManager`, `LeaseDao`, or lease entity classes.
- Worker or lease row deletion, worker-ID recycling, or runtime counter repair.
- A configurable worker limit beyond the range encoded by `workerBit`.
- General deadlock retry, a rescan/retry loop for concurrent false exhaustion, or a
  new cross-database registration abstraction.

## 3. Design

### 3.1 Counter identity and capacity

Add a general counter table with an auto-generated `counter_id`, unique immutable
`VARCHAR(191)` ASCII/`ascii_bin` `counter_ref`, non-unique `VARCHAR(128)`
human-readable `counter_name`, `BIGINT next_value`, and millisecond creation/update
timestamps. The canonical worker counter reference is:

```text
worker-id:domain:{domain_id}
```

Domain provisioning creates the counter with `next_value = 0`. Runtime registration
requires the row to exist and never creates or repairs it. The maximum permitted
worker ID is `IdCodec.getMaxWorker()` for the domain; no database `CHECK` constraint is
added for this application-derived range.

### 3.2 Existing-worker acquisition

Discovery uses non-locking reads and deterministic ascending `worker_id` order. A
worker is eligible when it has no lease row or its retained lease has reached
`reusable_at`. The existing lease API remains the concurrency authority:

- For an unleased worker, the unique lease resource key makes a duplicate insert a
  normal acquisition loss.
- For a reusable worker, conditional takeover rechecks `reusable_at` and the observed
  fencing token; zero affected rows is a normal acquisition loss.

Discovery does not retain `FOR UPDATE` locks. A caller that loses a candidate continues
with the next discovered candidate.

### 3.3 Worker registration transaction

When existing candidates are exhausted, a MySQL-only collaborator opens a new
non-auto-commit `MysqlDaoSession` and performs:

```text
begin
  select counter by canonical counter_ref for update
  validate generator belongs to the domain
  validate next_value <= IdCodec.getMaxWorker()
  insert workers row with worker_id = next_value
  update counter where counter_id and next_value still match
  require exactly one counter row updated
commit
close session
```

The worker insert and counter increment commit or roll back together, so a failed
registration does not consume finite worker-ID capacity. `worker_id` remains distinct
from the workers table surrogate `id`, and `UNIQUE(domain_id, worker_id)` remains a
defensive invariant. Same-domain allocations serialize on one counter; different
domains use independent rows.

### 3.4 Runtime lifecycle integration

After all loaded candidates have been tried, `AbstractGeneratorRuntimeManager` invokes
one protected extension point that may return one newly committed
`GeneratorProperties` candidate. Its default performs no registration, preserving
existing non-MySQL behavior. The base manager sends a returned candidate through the
existing `beforeCreate`, factory, `start`, and final publication sequence.

The MySQL loader and start path accept an empty immutable worker list so first startup
can reach the extension point. `startGeneratorInstance(generatorId, workerId)` remains
registered-worker-only and never allocates the requested ID or advances a counter.
Local prechecks and the final publication check remain authoritative within one
manager; database lease acquisition remains authoritative across managers/processes.

The MySQL registration collaborator is stateless apart from immutable DAO/config
references and is injectable through the test-oriented runtime-manager constructor.
It returns only after committing and closing its session. Lease acquisition continues
through the existing `beforeCreate` hook after that return.

### 3.5 Schema and DAO integration

Add `counters` to the clean-install schema in
`src/main/resources/mysql/lease.sql`. Add `CounterEntity`; `CounterDao` provides
exact-reference locking reads and conditional increments, while `WorkerDao` provides
insertion and stable candidate discovery. Both DAOs are registered in
`MysqlDaoFactory` and exposed by `MysqlDaoSession`.

## 4. Required Behavior

### REQ-1 — Reuse before allocation

An automatic MySQL start shall attempt eligible retained workers in stable order and
allocate a new worker only after those lease attempts are exhausted. Lease contention
is a skippable candidate loss, not proof of configuration failure.

### REQ-2 — Atomic domain-scoped allocation

Each registration call shall use its own transaction and lock only the exact canonical
counter row. It shall validate domain ownership and encoded capacity, then atomically
insert the worker and advance the observed counter by one. Same-domain calls must
produce distinct IDs; different domains must not share a counter lock.

### REQ-3 — Commit-before-lease boundary

The worker transaction shall commit and close before lease acquisition, generator
construction or `start()`, publication, callbacks, hooks, or external logging/metrics
exporters. No path may lock a worker or lease row and then request its counter lock.
The global order is counter lock, worker insert, commit, then lease insert/takeover;
reuse, renewal, and release touch only the lease row.

### REQ-4 — Recoverable durable state

A committed unleased worker shall remain discoverable for a later start. Lease rows
shall remain retained across release and expiration. Failure after lease acquisition
shall trigger best-effort release; otherwise the lease expires and becomes reusable
only after its cooling period.

### REQ-5 — Runtime and compatibility boundaries

Only automatic MySQL start may invoke dynamic registration. Exact-worker start shall
remain lookup-only, the public runtime interface shall not change, and non-MySQL
managers shall retain their current registration and startup behavior.

### REQ-6 — Session, DAO, and signal isolation

The registration collaborator shall not cache or share sessions, mappers, connections,
or transaction objects. DAO and runtime failures shall expose project-level signals
that distinguish missing counter, encoded capacity exhaustion, normal lease loss,
registration transaction failure, and lease database failure/timeout.

## 5. Failure Behavior

- A missing counter or domain/generator mismatch fails closed without creating a
  counter, worker, lease, or generator.
- `next_value` beyond the encoded maximum fails as capacity exhaustion and leaves the
  counter unchanged.
- Worker insert failure, conditional counter-update failure, or transaction failure
  rolls back both worker and counter changes and is translated to a project exception.
- Losing an unleased insert or reusable takeover is a normal candidate miss; after all
  candidates are lost, the start path may register one new worker.
- Lease failure after worker commit leaves a valid unleased worker for a later start.
  Generator creation/start/publication failure after acquisition releases best-effort
  or relies on expiry and cooling.
- The accepted scan/allocation race may report exhaustion even if another concurrent
  start just created a claimable worker; this phase adds no rescan or general retry.
- Raw SQL/persistence exceptions do not escape public runtime operations.

## 6. Acceptance Criteria

### AC-1 — First start and lifecycle ordering

With a configured generator and no workers, the first automatic start commits worker
`0`, advances its counter to `1`, then acquires a lease and publishes a generator;
instrumentation proves no lease, factory, start, or publication work occurs before the
worker transaction closes.

### AC-2 — Existing-worker preference and exact-start compatibility

An eligible existing worker starts without invoking registration, exhausted existing
candidates invoke registration once, and exact-worker start never creates a worker or
changes a counter.

### AC-3 — Concurrent allocation and rollback

Real-MySQL tests prove same-domain concurrent registrations allocate distinct worker
IDs, different domains progress independently, and worker-insert or counter-update
failure rolls back without consuming a value.

### AC-4 — Lease competition and recovery

Concurrent claims of one unleased worker and one reusable worker each have exactly one
winner; lease-acquisition failure leaves the committed worker claimable, and restart
reuses retained workers only after `reusable_at`.

### AC-5 — Capacity and configuration failures

Tests prove missing counters and encoded capacity exhaustion fail closed with distinct
project signals and no partial database or runtime state.

### AC-6 — Clean-install schema integration

A database created from `lease.sql` contains the counter table with the specified
unique reference and locking fields. Domain provisioning creates exactly one canonical
counter initialized to `0`, and the DAO layer can lock and advance it transactionally.

### AC-7 — Compatibility and build

Unit coverage verifies the base-manager extension behavior and injectable MySQL
collaborator, existing lease tests remain unchanged in meaning, non-MySQL manager tests
continue to pass, and the Maven build runs the new real-MySQL integration suite.

## 7. Required Configuration / Open Decisions

1. The runtime database principal requires `SELECT` on `domains` and
   `generator_configs`, `SELECT`/`UPDATE` on `counters`, `SELECT`/`INSERT` on
   `workers`, and `SELECT`/`INSERT`/`UPDATE` on `leases`, plus the deployed MySQL
   privilege required for `SELECT ... FOR UPDATE`; it needs no DDL or delete access.
2. Pin the test-only MySQL container dependency/version and lifecycle integration in
   `pom.xml`; the repository currently has no real-MySQL test harness.
3. Assign stable project error codes or exception types for missing counter, encoded
   worker capacity exhaustion, and worker registration transaction failure without
   changing existing lease error semantics.
