package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.zed.idgen.snowflake.core.manager.mysql.entity.CounterEntity;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class CounterDaoMysqlTest {
    private static final String LOWER_REF = CounterEntity.workerIdDomainRef(42L);
    private static final String UPPER_REF = "WORKER-ID:DOMAIN:42";

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
                     CounterDaoMysqlTest.class.getResourceAsStream("/mysql/lease.sql"),
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
    void seedCounters() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM counters");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO counters(counter_ref, counter_name, next_value)
                     VALUES (?, 'Domain worker IDs', ?)
                     """)) {
            delete.executeUpdate();

            insert.setString(1, LOWER_REF);
            insert.setLong(2, 7L);
            insert.executeUpdate();

            insert.setString(1, UPPER_REF);
            insert.setLong(2, 99L);
            insert.executeUpdate();
        }
    }

    @Test
    void locksExactReferenceAndConditionallyAdvancesObservedRow() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (MysqlDaoSession firstSession = daoFactory.openSession()) {
            CounterEntity observed = firstSession.counterDao().findByRefForUpdate(LOWER_REF);

            assertNotNull(observed.getCounterId());
            assertEquals(LOWER_REF, observed.getCounterRef());
            assertEquals(7L, observed.getNextValue());
            assertNotNull(observed.getCreatedAt());
            assertNotNull(observed.getUpdatedAt());

            CountDownLatch secondReadStarted = new CountDownLatch(1);
            CompletableFuture<CounterEntity> secondRead = CompletableFuture.supplyAsync(() -> {
                try (MysqlDaoSession secondSession = daoFactory.openSession()) {
                    secondReadStarted.countDown();
                    CounterEntity counter = secondSession.counterDao().findByRefForUpdate(LOWER_REF);
                    secondSession.rollback();
                    return counter;
                }
            }, executor);

            assertTrue(secondReadStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> secondRead.get(300, TimeUnit.MILLISECONDS));

            assertEquals(1, firstSession.counterDao().advanceIfCurrent(
                    observed.getCounterId(), observed.getNextValue()));
            firstSession.commit();

            CounterEntity afterCommit = secondRead.get(5, TimeUnit.SECONDS);
            assertEquals(8L, afterCommit.getNextValue());
        } finally {
            executor.shutdownNow();
        }

        try (MysqlDaoSession session = daoFactory.openSession()) {
            CounterEntity current = session.counterDao().findByRefForUpdate(LOWER_REF);
            assertEquals(0, session.counterDao().advanceIfCurrent(current.getCounterId(), 7L));
            assertEquals(0, session.counterDao().advanceIfCurrent(current.getCounterId() + 1_000L, 8L));
            session.rollback();
        }

        assertEquals(8L, nextValue(LOWER_REF));
        assertEquals(99L, nextValue(UPPER_REF));
    }

    private long nextValue(String counterRef) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT next_value
                     FROM counters
                     WHERE counter_ref = ?
                     """)) {
            statement.setString(1, counterRef);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }
}
