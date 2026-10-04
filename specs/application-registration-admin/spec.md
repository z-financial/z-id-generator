# Application Registration Administration API

Status: Proposed | Last updated: 2026-10-04

## 1. Context

MySQL-backed deployments currently require domain and generator configuration to be
seeded outside the library. The existing `GeneratorRegistrator` placeholder cannot
provide safe provisioning because it accepts an already assigned generator ID, does
not identify the target domain, returns no persisted configuration, and exposes a
delete operation without defined lifecycle semantics.

Applications need a small in-process Java API that can provision immutable domain and
generator definitions during deployment or startup. Calls must be safe to retry and
safe under concurrent identical requests, while conflicting definitions must never be
accepted as successful retries.

Provisioning is an administrative concern. It must not create workers, acquire leases,
construct generators, or publish runtime instances.

## 2. Decision and Scope

Replace `GeneratorRegistrator` with a `RegistrationAdmin` API exposing:

```java
IdDomainInfo ensureDomain(DomainRegistrationRequest request);

GeneratorConfig ensureGenerator(
        long domainId,
        GeneratorRegistrationRequest request);
```

`DomainRegistrationRequest` contains `domainName`, `prefixType`, `timeBit`,
`workerBit`, `sequenceBit`, `epochStartMs`, and `timeUnit`.
`GeneratorRegistrationRequest` contains `generatorName` and `generationPolicy`.
Both operations return the authoritative persisted model, including the
database-generated ID. Callers cannot supply database IDs, counter values, or worker
IDs.

```java
record DomainRegistrationRequest(
        String domainName,
        PrefixType prefixType,
        int timeBit,
        int workerBit,
        int sequenceBit,
        long epochStartMs,
        TimeUnit timeUnit) {}

record GeneratorRegistrationRequest(
        String generatorName,
        GeneratorPolicy generationPolicy) {}
```

### Included

- Public request records and the `RegistrationAdmin` contract.
- A thread-safe MySQL implementation using one DAO session per call or transaction.
- Idempotent creation and exact conflict detection for domains and generators.
- Atomic creation of a domain and its worker-ID counter.
- DAO operations and project-level errors needed by the contract.
- Unit and real-MySQL verification of validation, equality, concurrency, and rollback.

### Unchanged / out of scope

- HTTP or another remote administration transport.
- Update, delete, or unregister operations.
- Worker allocation, worker or lease creation, and lease lifecycle operations.
- Generator construction, startup, or runtime publication.
- Automatic repair of inconsistent existing registration data.
- Changes to non-MySQL registration formats or runtime start APIs.

## 3. Design

### 3.1 Identity and idempotency

`domain_name` is the natural key for `ensureDomain`. If it is absent, the operation
creates the domain and its counter. If it exists, every persisted definition field
must equal the request; equality returns the existing `IdDomainInfo`, while any
difference is a configuration conflict.

`(domain_id, generator_name)` is the natural key for `ensureGenerator`. If it is
absent under an existing domain, the operation creates the generator configuration.
If it exists, its deserialized `GeneratorPolicy` must equal the request. Semantic
policy equality must not depend on JSON property order, whitespace, or another
non-semantic serialization difference.

The database assigns `domain_id` and `generator_id`. Existing definitions are
immutable through this API; changing one requires a separately designed explicit API.

### 3.2 Domain transaction

Creating a domain uses one non-auto-commit `MysqlDaoSession`:

```text
validate request
begin
  insert domains row
  read generated domain_id
  insert counters row:
    counter_ref  = "worker-id:domain:" + domain_id
    counter_name = human-readable worker-ID counter description
    next_value   = 0
commit
```

The domain and counter commit or roll back together. The transaction contains only
database work and closes before callbacks, runtime startup, lease calls, or other
external work. A committed domain without its counter is not a successful result.

When an existing domain is returned, the canonical counter must already exist. A
missing counter is an integrity/configuration failure and is not repaired implicitly.

### 3.3 Generator transaction

Generator creation is independent from domain creation; a domain with no generators
is valid. In one short transaction, the implementation validates the request, verifies
the domain by `domain_id`, inserts the generator configuration, and commits. It does
not create a worker or lease row.

### 3.4 Concurrency and ownership

MySQL unique keys arbitrate concurrent creation; no application-level lock is added.
If an insert loses a natural-key race, its transaction rolls back before the operation
reloads the winner in a new session and applies the idempotency comparison. Matching
definitions return the winner; non-matching definitions fail as conflicts.

The implementation retains only immutable configuration and DAO-factory references.
Sessions, mappers, connections, and transaction objects are call-scoped and never
shared across threads. These operations do not lock worker or lease rows and therefore
do not reverse the runtime lock order.

## 4. Required Behavior

### REQ-1 — Public administrative contract

The library shall expose the two ensure operations and request records above, replace
the insufficient `GeneratorRegistrator` contract, and return the persisted
`IdDomainInfo` or `GeneratorConfig` with its database-generated ID. No unregister or
caller-assigned-ID behavior shall be carried into the replacement API.

### REQ-2 — Validation and representation

Names, domain layout, epoch, time unit, prefix type, and generator policy shall be
validated before a transaction when possible and again through the established model
invariants. Generator policy persistence shall round-trip through the same supported
semantic representation used by MySQL registration loading.

### REQ-3 — Idempotent immutable provisioning

Each natural key shall identify one immutable definition. Repeating an equivalent
request, including after a concurrent insert race, shall return that definition;
reusing the key with any materially different field or policy shall fail closed.

### REQ-4 — Atomic domain and counter creation

A newly created domain and its canonical counter shall be committed in the same
transaction with `next_value = 0`. Neither partial persistence nor runtime repair of a
missing counter is permitted.

### REQ-5 — Isolation from runtime lifecycle

Provisioning transactions shall perform database work only, use call-scoped sessions,
and complete before any runtime, lease, callback, hook, logging exporter, or metrics
exporter invocation. They shall not create, lock, update, or delete worker or lease
rows.

## 5. Failure Behavior

- Invalid request data fails before mutation and is reported as a project-level
  validation/configuration error.
- A missing `domain_id` during generator creation fails without inserting a generator.
- An existing natural key with a different definition or semantic policy fails as a
  distinguishable configuration conflict and leaves the existing row unchanged.
- Failure to insert the counter rolls back the new domain; failure to insert a
  generator rolls back that generator transaction.
- A missing counter for an existing domain is reported as an integrity/configuration
  error and is not silently repaired.
- Database failures and timeouts are translated to project exceptions; raw SQL or
  persistence exceptions do not escape the public API.

## 6. Acceptance Criteria

### AC-1 — API and legacy replacement

The new public types compile for Java 21, return generated IDs, and all production and
test references to `GeneratorRegistrator` are removed or replaced; no unregister
operation remains in the administration contract.

### AC-2 — Domain idempotency and conflict handling

Real-MySQL tests prove that sequential and concurrent equivalent domain requests
return one persisted definition and one counter, while every differing definition
field produces a conflict without mutation.

### AC-3 — Atomic domain provisioning

An injected or database-induced counter insertion failure leaves neither a committed
domain nor a counter, and a pre-existing domain with a missing counter fails without
repair.

### AC-4 — Generator idempotency and semantic equality

Tests prove that sequential and concurrent equivalent generator requests create one
row, semantically equivalent policy JSON compares equal regardless of formatting, and
a different policy produces a conflict.

### AC-5 — Boundary and thread safety

Concurrent calls use independent sessions and can safely share one admin instance;
inspection and tests demonstrate that no worker, lease, runtime, or external callback
work occurs inside either provisioning transaction.

### AC-6 — Validation and error contract

Unit and integration tests distinguish invalid definitions, unknown domains,
definition conflicts, missing counters, and database failure/timeout without exposing
raw persistence exceptions.

## 7. Required Configuration / Open Decisions

1. The `counters` schema and canonical counter reference from the dynamic worker
   registration SPEC must be deployed before this API provisions domains.
2. The administrative database principal requires `SELECT` and `INSERT` on
   `domains`, `generator_configs`, and `counters`; it does not require worker or lease
   mutation permissions for this API.
3. Before implementation is complete, assign stable project exception types or error
   codes for configuration conflict, missing counter/integrity failure, and database
   operation failure or timeout.
