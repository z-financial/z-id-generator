package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlWorkerRegistrationException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.entity.CounterEntity;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class MysqlWorkerRegistrarTest {
    private static final String ORDERS = "orders";
    private static final String BILLING = "billing";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0");

    private static PooledDataSource dataSource;
    private static MysqlDaoFactory daoFactory;

    @BeforeAll
    static void createSchema() throws Exception {
        dataSource = new PooledDataSource(
                MYSQL.getDriverClassName(),
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection();
             InputStreamReader schema = new InputStreamReader(
                     MysqlWorkerRegistrarTest.class.getResourceAsStream("/mysql/lease.sql"),
                     StandardCharsets.UTF_8)) {
            new ScriptRunner(connection).runScript(schema);
        }
        daoFactory = new MysqlDaoFactory(dataSource, 5, 2);
    }

    @AfterAll
    static void closeDataSource() {
        if (daoFactory != null) {
            daoFactory.close();
        }
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @BeforeEach
    void resetSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS force_counter_change");
            statement.executeUpdate("DELETE FROM leases");
            statement.executeUpdate("DELETE FROM workers");
            statement.executeUpdate("DELETE FROM generator_configs");
            statement.executeUpdate("DELETE FROM counters");
            statement.executeUpdate("DELETE FROM domains");
        }
    }

    @Test
    void concurrentRegistrationsAllocateDistinctDomainScopedWorkerIds() throws Exception {
        RegistrationData orders = seedRegistration(ORDERS, 0L);
        RegistrationData billing = seedRegistration(BILLING, 0L);
        WorkerRegistrar ordersRegistrar = new MysqlWorkerRegistrar(daoFactory, ORDERS);
        WorkerRegistrar billingRegistrar = new MysqlWorkerRegistrar(daoFactory, BILLING);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            CompletableFuture<GeneratorProperties> first = registerAsync(
                    executor, start, ordersRegistrar, orders.generatorId());
            CompletableFuture<GeneratorProperties> second = registerAsync(
                    executor, start, ordersRegistrar, orders.generatorId());
            CompletableFuture<GeneratorProperties> otherDomain = registerAsync(
                    executor, start, billingRegistrar, billing.generatorId());
            start.countDown();

            assertEquals(Set.of(0L, 1L), Set.of(first.get().workerId(), second.get().workerId()));
            assertEquals(0L, otherDomain.get().workerId());
        }

        assertEquals(2L, nextValue(orders.domainId()));
        assertEquals(1L, nextValue(billing.domainId()));
        assertEquals(3L, workerCount());
    }

    @Test
    void configurationAndCapacityFailuresDoNotMutateState() throws Exception {
        RegistrationData orders = seedRegistrationWithoutCounter(ORDERS);
        RegistrationData billing = seedRegistration(BILLING, 0L);
        WorkerRegistrar registrar = new MysqlWorkerRegistrar(daoFactory, ORDERS);

        MysqlWorkerRegistrationException missing = assertThrows(
                MysqlWorkerRegistrationException.class,
                () -> registrar.registerNext(orders.generatorId()));
        assertEquals(SnowflakeErrorCode.MysqlWorkerCounterMissing, missing.getErrorCode());

        assertThrows(InvalidConfigException.class, () -> registrar.registerNext(billing.generatorId()));
        insertCounter(orders.domainId(), 1_024L);
        MysqlWorkerRegistrationException exhausted = assertThrows(
                MysqlWorkerRegistrationException.class,
                () -> registrar.registerNext(orders.generatorId()));
        assertEquals(SnowflakeErrorCode.MysqlWorkerCapacityExhausted, exhausted.getErrorCode());
        assertEquals(1_024L, nextValue(orders.domainId()));
        assertEquals(0L, workerCount());
    }

    @Test
    void persistenceFailuresRollBackWithoutConsumingWorkerId() throws Exception {
        RegistrationData orders = seedRegistration(ORDERS, 0L);
        insertWorker(orders, 0L);

        MysqlWorkerRegistrationException insertFailure = assertThrows(
                MysqlWorkerRegistrationException.class,
                () -> new MysqlWorkerRegistrar(daoFactory, ORDERS).registerNext(orders.generatorId()));
        assertEquals(SnowflakeErrorCode.MysqlWorkerRegistrationFailed, insertFailure.getErrorCode());
        assertEquals(0L, nextValue(orders.domainId()));
        assertEquals(1L, workerCount());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM workers");
            statement.execute("""
                    CREATE TRIGGER force_counter_change
                    AFTER INSERT ON workers
                    FOR EACH ROW
                    UPDATE counters
                    SET next_value = next_value + 100
                    WHERE counter_ref = CONCAT('worker-id:domain:', NEW.domain_id)
                    """);
        }

        MysqlWorkerRegistrationException failure = assertThrows(
                MysqlWorkerRegistrationException.class,
                () -> new MysqlWorkerRegistrar(daoFactory, ORDERS).registerNext(orders.generatorId()));

        assertEquals(SnowflakeErrorCode.MysqlWorkerRegistrationFailed, failure.getErrorCode());
        assertEquals(0L, nextValue(orders.domainId()));
        assertEquals(0L, workerCount());
    }

    @Test
    void loaderReturnsEmptyOrEligibleWorkersInStableOrder() throws Exception {
        RegistrationData orders = seedRegistration(ORDERS, 0L);
        MysqlRegistrationLoader loader = new MysqlRegistrationLoader(daoFactory, ORDERS);

        List<GeneratorProperties> empty = loader.loadCandidateProperties(orders.generatorId());
        assertEquals(List.of(), empty);
        assertThrows(UnsupportedOperationException.class, () -> empty.add(null));

        insertWorker(orders, 2L);
        insertWorker(orders, 0L);
        insertWorker(orders, 1L);
        insertLeaseStates();

        List<Long> eligibleWorkerIds = loader.loadCandidateProperties(orders.generatorId()).stream()
                .map(GeneratorProperties::workerId)
                .toList();
        assertEquals(List.of(1L, 2L), eligibleWorkerIds);
        assertEquals(List.of(0L, 1L, 2L), loader.loadGeneratorProperties(orders.generatorId()).stream()
                .map(GeneratorProperties::workerId)
                .toList());
    }

    private static CompletableFuture<GeneratorProperties> registerAsync(
            ExecutorService executor,
            CountDownLatch start,
            WorkerRegistrar registrar,
            long generatorId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
                return registrar.registerNext(generatorId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    private static RegistrationData seedRegistration(String domainName, long nextValue) throws Exception {
        RegistrationData data = seedRegistrationWithoutCounter(domainName);
        insertCounter(data.domainId(), nextValue);
        return data;
    }

    private static RegistrationData seedRegistrationWithoutCounter(String domainName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long domainId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO domains(
                        domain_name, prefix_type, time_bit, worker_bit,
                        sequence_bit, epoch_start_ms, time_unit)
                    VALUES (?, 'POSITIVE_SIGNAL', 41, 10, 12, 1700000000000, 'MILLISECOND')
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, domainName);
                statement.executeUpdate();
                domainId = generatedId(statement);
            }

            long generatorId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO generator_configs(domain_id, generator_name, policy)
                    VALUES (?, ?, JSON_OBJECT(
                        'maximumRetry', 1,
                        'idRunOutPolicy', 'NONE',
                        'clockRollbackPolicy', 'NONE'))
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, domainId);
                statement.setString(2, domainName + "-generator");
                statement.executeUpdate();
                generatorId = generatedId(statement);
            }
            return new RegistrationData(domainId, generatorId);
        }
    }

    private static void insertCounter(long domainId, long nextValue) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO counters(counter_ref, counter_name, next_value)
                     VALUES (?, 'Domain worker IDs', ?)
                     """)) {
            statement.setString(1, CounterEntity.workerIdDomainRef(domainId));
            statement.setLong(2, nextValue);
            statement.executeUpdate();
        }
    }

    private static void insertWorker(RegistrationData data, long workerId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO workers(domain_id, generator_id, worker_id)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setLong(1, data.domainId());
            statement.setLong(2, data.generatorId());
            statement.setLong(3, workerId);
            statement.executeUpdate();
        }
    }

    private static void insertLeaseStates() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO leases(
                        lease_domain, lease_ref, lease_description,
                        owner_id, owner_token, fencing_token,
                        renewed_at, expired_at, reusable_at)
                    VALUES
                        ('orders', 0, 'active', 'node-a', 'active-token', 0,
                         UTC_TIMESTAMP(3),
                         TIMESTAMPADD(MINUTE, 1, UTC_TIMESTAMP(3)),
                         TIMESTAMPADD(MINUTE, 2, UTC_TIMESTAMP(3))),
                        ('orders', 1, 'reusable', 'node-b', 'reusable-token', 0,
                         TIMESTAMPADD(SECOND, -3, UTC_TIMESTAMP(3)),
                         TIMESTAMPADD(SECOND, -2, UTC_TIMESTAMP(3)),
                         TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3)))
                    """);
        }
    }

    private static long generatedId(PreparedStatement statement) throws Exception {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static long nextValue(long domainId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT next_value FROM counters WHERE counter_ref = ?")) {
            statement.setString(1, CounterEntity.workerIdDomainRef(domainId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long workerCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM workers")) {
            result.next();
            return result.getLong(1);
        }
    }

    private record RegistrationData(long domainId, long generatorId) {
    }
}
