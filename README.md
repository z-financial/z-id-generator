# z-id-generator

A Java 21 library for generating distributed, Snowflake-style 64-bit IDs.

It supports:

- configurable time, worker, and sequence bit layouts;
- single-ID and batch generation;
- multiple named generators and workers;
- in-memory, local-file, and MySQL-backed registration;
- renewable MySQL worker leases to prevent two nodes from using the same worker ID.

## Requirements

- JDK 21
- Maven
- MySQL 8 for MySQL-backed registration
- Docker for the Testcontainers integration tests

## Build and install locally

```bash
mvn -DskipTests install
```

Then add the library to another Maven project:

```xml
<dependency>
    <groupId>com.zed.idgen</groupId>
    <artifactId>z-id-generator</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Quick start

The in-memory manager is the simplest option for one process:

```java
import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfiguredGeneratorRuntimeManager;

import java.util.List;

var domain = new ConfigRuntimeConfig.IdDomainConfig(
        1L, "orders", PrefixType.POSITIVE_SIGNAL,
        41, 10, 12, 1_700_000_000_000L, TimeUnit.MILLISECOND);

var policy = new ConfigRuntimeConfig.GenerationPolicyConfig(
        1, IdRunOutPolicy.NONE, ClockRollbackPolicy.NONE);

var config = new ConfigRuntimeConfig(
        domain,
        List.of(new ConfigRuntimeConfig.GeneratorConfig(
                1L, "order-id", policy, 0L)));

try (var manager = new ConfiguredGeneratorRuntimeManager(config)) {
    long generatorId = manager.resolveGeneratorId("order-id");
    IdGenerator generator = manager.startGeneratorInstance(generatorId);

    long id = generator.getUid();
    long[] ids = generator.getUidByBatch(100);
}
```

The bit fields must total 64 bits. `POSITIVE_SIGNAL` reserves one sign bit, so the example uses `1 + 41 + 10 + 12 = 64`.

## Runtime modes

| Manager | Use case |
| --- | --- |
| `ConfiguredGeneratorRuntimeManager` | Immutable configuration in one process |
| `LocalFileGeneratorRuntimeManager` | Configuration stored on one machine |
| `MysqlGeneratorRuntimeManager` | Multiple application nodes with dynamic worker registration and leases |

Always close a manager when the application stops. Closing it stops its generators and releases mode-specific resources.

### Local-file mode

Create this structure relative to the application's working directory:

```text
uid-register/
  orders/
    domain.yml
    generator/
      1-0-order-id.yml
```

`domain.yml`:

```yaml
domainId: 1
domainName: orders
prefixType: POSITIVE_SIGNAL
timeBit: 41
workerBit: 10
sequenceBit: 12
epochStartMs: 1700000000000
timeUnit: MILLISECOND
```

`generator/1-0-order-id.yml`:

```yaml
generationPolicy:
  maximumRetry: 1
  idRunOutPolicy: NONE
  clockRollbackPolicy: NONE
```

Start it with:

```java
try (var manager = new LocalFileGeneratorRuntimeManager("orders")) {
    IdGenerator generator = manager.startGeneratorInstance(
            manager.resolveGeneratorId("order-id"));
    long id = generator.getUid();
}
```

Local-file mode creates a `running.placeholder` file to guard against accidental multi-process use.

### MySQL mode

1. Apply [`src/main/resources/mysql/lease.sql`](src/main/resources/mysql/lease.sql).
2. Create a `MysqlDaoFactory` from a `MysqlConfig` or an application-managed `DataSource`.
3. Provision the domain and generator with `MysqlRegistrationAdmin`.
4. Create `MysqlGeneratorRuntimeManager` with a unique `serverInstanceId` for each node.

Calling `startGeneratorInstance(generatorId)` selects an available worker, registers one when necessary, acquires its lease, and renews the lease in the background. If lease safety can no longer be guaranteed, generation stops rather than risking duplicate IDs.

## Concurrency control and lease safety

The design protects ID uniqueness at three levels:

1. **Thread safety:** each provider serializes its timestamp and sequence updates. Batch generation reserves a complete sequence range atomically.
2. **Process safety:** the runtime manager keeps worker indexes behind short read/write locks. Slow configuration and database work is performed outside those map locks.
3. **Distributed safety:** MySQL grants time-bounded ownership of each worker ID. A node must acquire the worker lease before its generator is published.

### Lease identity and fencing

A lease resource is identified by `(lease_domain, lease_ref)`. Ownership uses three values:

| Field | Purpose |
| --- | --- |
| `owner_id` | Stable node identity for operations and diagnostics |
| `owner_token` | Unique identity for one process or restart |
| `fencing_token` | Monotonic generation number advanced on every takeover |

Renewal and release match the complete ownership identity. A delayed request from an older owner therefore cannot overwrite a newer lease generation.

### Lease state machine

```text
ABSENT  -- acquire -------------------------------> ACTIVE
ACTIVE  -- renew ---------------------------------> ACTIVE
ACTIVE  -- expiry or release ---------------------> COOLING
COOLING -- reusable_at reached; fenced takeover --> ACTIVE
```

- **Active:** the current owner may renew the lease.
- **Cooling:** the previous owner is invalid, but the worker cannot be reused yet.
- **Reusable:** a new owner may take over and increment the fencing token.

MySQL time is authoritative for these transitions. Acquisition uses a transaction and row lock; concurrent first inserts are resolved by the unique resource key. Takeover and renewal use conditional updates, so an affected-row count of zero means contention or stale ownership rather than success.

### Fail-closed local deadline

Every leased generator also has a monotonic local deadline shorter than the database lease:

```text
local safe term < database lease term
renew interval   < local safe term
```

The deadline is measured before acquire or renew I/O. Database latency consumes the safety window instead of extending it. Generation checks the deadline both before and after producing IDs, which protects against long pauses during generation. Failed or ambiguous renewals never extend local time; continued failure stops the generator.

### Safe worker reuse

A new generator starts its sequence at zero, so expiry alone is not enough for safe reuse. The lease remains unavailable for:

```text
cooling period = generator time unit + maximum relative clock skew + safety margin
```

This moves the new owner beyond any timestamp bucket reachable by the previous owner. Lease rows are retained rather than deleted, preserving both the cooling barrier and fencing history.

The result is deliberately fail-closed: database outages, scheduler delays, or renewal saturation may reduce availability, but they do not silently relax the uniqueness guarantee.

## Testing

Run a focused unit test:

```bash
mvn -Dtest=ConfiguredGeneratorRuntimeManagerTest test
```

MySQL integration tests use Testcontainers and require Docker. Run benchmark and concurrency tests only when you specifically need them.
