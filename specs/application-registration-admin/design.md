# Application Registration Administration API — Design

Status: Proposed  
Source of truth: [SPEC](./spec.md)  
Delivery tracking: [tasks](./task.md)

## 1. Purpose

Provide a small in-process Java API for provisioning MySQL domain and generator
definitions. Provisioning is administrative and stops at persisted configuration: it
does not create workers, acquire leases, construct generators, or publish runtime
instances.

The design replaces the incomplete `GeneratorRegistrator` contract with retry-safe,
immutable ensure operations. MySQL unique keys arbitrate concurrent creation, while
the implementation distinguishes an equivalent retry from a conflicting definition.

The canonical worker-counter format and runtime consumer are defined in the sibling
[Dynamic Worker Registration design](../dynamic-worker-registration/design.md).

## 2. Design boundaries

### Goals

- Return authoritative persisted models with database-generated IDs.
- Make equivalent sequential and concurrent calls idempotent.
- Reject natural-key reuse with a materially different definition.
- Commit a new domain and its worker-ID counter atomically.
- Use one call-scoped `MysqlDaoSession` per read or transaction.
- Translate persistence failures into stable project-level errors.

### Non-goals

- update, delete, or unregister operations;
- worker allocation or lease lifecycle changes;
- runtime construction, startup, or publication;
- remote administration transports;
- implicit repair of inconsistent registration data; and
- changes to local-file or configured/in-memory registration.

## 3. Public API

The replacement contract is:

```java
public interface RegistrationAdmin {

    IdDomainInfo ensureDomain(DomainRegistrationRequest request);

    GeneratorConfig ensureGenerator(
            long domainId,
            GeneratorRegistrationRequest request);
}
```

```java
public record DomainRegistrationRequest(
        String domainName,
        PrefixType prefixType,
        int timeBit,
        int workerBit,
        int sequenceBit,
        long epochStartMs,
        TimeUnit timeUnit) {
}
```

```java
public record GeneratorRegistrationRequest(
        String generatorName,
        GeneratorPolicy generationPolicy) {
}
```

`GeneratorRegistrator` and its `unregisterGenerator` operation are removed. Callers
cannot provide a `domainId`, `generatorId`, counter value, or worker ID for insertion.

The records should reject null values and structurally invalid values at construction
or at the public service boundary. The established model constructors remain the
second validation boundary when persisted models are reconstructed.

## 4. Component responsibilities

| Component | Responsibility |
| --- | --- |
| `RegistrationAdmin` | Public administrative contract. |
| `DomainRegistrationRequest` | Immutable input for a domain definition. |
| `GeneratorRegistrationRequest` | Immutable input for a generator definition. |
| `MysqlRegistrationAdmin` | Validation, transactions, idempotency, race recovery, and error translation. |
| `DomainDao` | Insert a domain and find it by exact natural key or ID. |
| `GeneratorConfigDao` | Insert a generator and find it by `(domain_id, generator_name)`. |
| `CounterDao` | Insert and verify the canonical worker-ID counter. |
| Shared policy codec | Serialize and deserialize the supported `GeneratorPolicy` representation. |

`MysqlRegistrationAdmin` holds only immutable collaborators, primarily a
`MysqlDaoFactory` and the policy codec. It never stores a session, mapper, connection,
or transaction in an instance field.

The policy JSON conversion currently embedded in `MysqlRegistrationLoader` should be
extracted into one package-level codec and reused by loading and administration. This
prevents the write path from accepting a policy representation that the read path
cannot reconstruct. Idempotency compares deserialized `GeneratorPolicy` values, not
raw JSON strings.

## 5. Validation and normalization

Validation occurs before opening a mutation transaction whenever it needs no database
state:

- names are non-null, non-blank, normalized consistently with existing lookup rules,
  and fit the schema limits;
- prefix type and time unit are supported values;
- bit counts and epoch produce a valid `IdDomainInfo`/`IdCodec` layout;
- generator policy satisfies `GeneratorPolicy` invariants; and
- `domainId` supplied to `ensureGenerator` is non-negative.

The implementation must not introduce case folding or whitespace normalization that
would disagree with the database natural key. If names are trimmed, the normalized
value is used for both lookup and insert.

## 6. Domain ensure flow

The fast path first performs an exact-name read in an auto-commit session. If the row
exists, the implementation compares every immutable field and verifies that the
canonical counter exists.

```text
validate and normalize request
read domain by domain_name
if found:
  compare all definition fields
  require canonical counter
  return authoritative IdDomainInfo

open non-auto-commit session
try:
  insert domain
  require generated domain_id
  insert counter(
      counter_ref  = "worker-id:domain:" + domain_id,
      counter_name = human-readable description,
      next_value   = 0)
  commit
  return inserted IdDomainInfo
catch natural-key duplicate:
  rollback and close
  reload winner in a new session
  compare definition and require counter
  return winner or raise conflict
catch any other failure:
  rollback and translate
finally:
  close session
```

The duplicate-key recovery applies only when the violated key represents the expected
creation race. An unrelated constraint failure is a database operation failure, not an
idempotent retry.

### Domain equality

The comparison includes:

- `domainName`;
- `prefixType`;
- `timeBit`;
- `workerBit`;
- `sequenceBit`;
- `epochStartMs`; and
- `timeUnit`.

Database IDs and audit timestamps are not request fields and do not participate in
definition equality.

### Missing counter behavior

An existing matching domain without `worker-id:domain:{domain_id}` is inconsistent.
`ensureDomain` reports an integrity/configuration failure and leaves the database
unchanged. It must not repair or initialize the missing counter.

## 7. Generator ensure flow

Generator creation is independent of domain creation; a registered domain with no
generators is valid.

```text
validate and normalize request
read generator by (domain_id, generator_name)
if found:
  deserialize policy through the shared codec
  compare GeneratorPolicy values
  return authoritative GeneratorConfig

open non-auto-commit session
try:
  require domain_id to exist
  serialize policy through the shared codec
  insert generator_config
  require generated generator_id
  commit
  return inserted GeneratorConfig
catch natural-key duplicate:
  rollback and close
  reload winner in a new session
  compare semantic policy
  return winner or raise conflict
catch any other failure:
  rollback and translate
finally:
  close session
```

A missing domain fails before generator insertion. No worker or lease row is touched.

## 8. DAO and persistence changes

The existing entities already use `IdType.AUTO`; successful inserts must verify that
MyBatis populated the generated ID.

Required DAO capabilities:

- `DomainDao.findByName(String)` remains the natural-key read.
- Add a direct domain-by-ID read for generator validation if `selectById` is not used.
- Use `BaseMapper.insert` or an explicit insert for `DomainEntity`.
- Add `GeneratorConfigDao.findByName(long domainId, String generatorName)` returning
  the full row, rather than only the ID.
- Use `BaseMapper.insert` or an explicit insert for `GeneratorConfigEntity`.
- Add `CounterEntity` and `CounterDao` as owned by the dynamic-worker feature, with
  insert and exact-reference lookup available to this administrator.
- Register `CounterDao` in `MysqlDaoFactory` and expose it from `MysqlDaoSession`.

All equality checks are performed on mapped domain models. Callers never compare
persistence entities or serialized JSON.

## 9. Concurrency and transaction ownership

No JVM-wide or instance-level creation lock is added. The database keys remain the
serialization points:

- `domains.domain_name` for domains;
- `generator_configs(domain_id, generator_name)` for generators; and
- `counters.counter_ref` for the canonical counter identity.

The losing side of a natural-key race always rolls back and closes its transaction
before reloading the winner. Reloading through the failed transaction is forbidden
because its snapshot and error state may not expose the committed winner reliably.

Administrative transactions never lock worker or lease rows. They therefore cannot
reverse the runtime lock order of counter, worker insert, commit, then lease mutation.

## 10. Error contract

Stable exception classes or `SnowflakeErrorCode` values must distinguish:

| Condition | Public classification |
| --- | --- |
| Invalid request or model invariant | Validation/configuration error |
| Missing domain during generator creation | Unknown-domain configuration error |
| Same natural key, different definition | Registration conflict |
| Existing domain missing its counter | Registration integrity failure |
| SQL timeout | Database operation timeout |
| Other persistence failure | Database operation failure |

Raw `SQLException`, MyBatis `PersistenceException`, and vendor messages do not cross
the public API. The original cause may be retained for diagnostics.

## 11. Schema and permissions

The `counters` table must be deployed before `ensureDomain` is enabled. The table and
canonical identity are defined by the dynamic-worker design.

The administrative principal requires:

```text
domains:           SELECT, INSERT
generator_configs: SELECT, INSERT
counters:          SELECT, INSERT
```

It does not need worker or lease mutation permissions for this API.

## 12. Verification strategy

### Unit tests

- request validation and name normalization;
- exact domain-field comparison;
- semantic policy equality through the shared codec;
- conflict and error translation; and
- proof that one admin instance never shares sessions between calls.

### Real-MySQL tests

- sequential and concurrent equivalent domain ensures create one domain and counter;
- each differing domain field causes a conflict without mutation;
- counter insertion failure rolls back the new domain;
- an existing domain with a missing counter fails without repair;
- sequential and concurrent equivalent generator ensures create one row;
- differently formatted but semantically equal policy JSON is idempotent;
- a different policy conflicts without mutation; and
- timeout and persistence failures do not expose raw database exceptions.

## 13. Delivery order and open decisions

1. Deliver the clean-install counter schema, entity, and DAO from the dynamic-worker
   feature.
2. Extract and test the shared generator-policy codec.
3. Implement the administrative API and DAO additions.
4. Remove production and test references to `GeneratorRegistrator`.
5. Add the real-MySQL test harness and concurrency/rollback coverage.

Before implementation is complete, assign stable project exception types or error
codes for conflict, missing-counter integrity failure, and database failure/timeout.
Backfilling counters for already-populated installations is a separate rollout design;
the runtime and this API intentionally do not infer or repair a missing counter.

