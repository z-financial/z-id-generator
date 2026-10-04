package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.exception.MysqlRegistrationAdminException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.manager.DomainRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.GeneratorRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.entity.CounterEntity;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MysqlRegistrationAdminTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0");

    private static PooledDataSource dataSource;
    private static MysqlDaoFactory daoFactory;
    private static MysqlRegistrationAdmin registrationAdmin;

    @BeforeAll
    static void createSchema() throws Exception {
        dataSource = new PooledDataSource(
                MYSQL.getDriverClassName(),
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection();
             InputStreamReader schema = new InputStreamReader(
                     MysqlRegistrationAdminTest.class.getResourceAsStream("/mysql/lease.sql"),
                     StandardCharsets.UTF_8)) {
            new ScriptRunner(connection).runScript(schema);
        }
        daoFactory = new MysqlDaoFactory(dataSource, 5, 2);
        registrationAdmin = new MysqlRegistrationAdmin(daoFactory);
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
            statement.execute("DROP TRIGGER IF EXISTS reject_counter_insert");
            statement.execute("DROP TRIGGER IF EXISTS reject_generator_insert");
            statement.executeUpdate("DELETE FROM leases");
            statement.executeUpdate("DELETE FROM workers");
            statement.executeUpdate("DELETE FROM generator_configs");
            statement.executeUpdate("DELETE FROM counters");
            statement.executeUpdate("DELETE FROM domains");
        }
    }

    @Test
    void equivalentEnsureReturnsOneDomainAndCanonicalCounter() throws Exception {
        DomainRegistrationRequest request = request("orders", 41, 10, 12);

        IdDomainInfo created = registrationAdmin.ensureDomain(request);
        IdDomainInfo repeated = registrationAdmin.ensureDomain(request);

        assertTrue(created.domainId() > 0);
        assertEquals(created, repeated);
        assertEquals(1L, count("domains"));
        assertEquals(1L, count("counters"));
        assertEquals(0L, counterNextValue(created.domainId()));
    }

    @Test
    void conflictingDefinitionAndMissingCounterFailWithoutRepair() throws Exception {
        IdDomainInfo domain = registrationAdmin.ensureDomain(request("orders", 41, 10, 12));

        MysqlRegistrationAdminException conflict = assertThrows(
                MysqlRegistrationAdminException.class,
                () -> registrationAdmin.ensureDomain(request("orders", 40, 11, 12)));
        assertEquals(SnowflakeErrorCode.MysqlRegistrationConflict, conflict.getErrorCode());
        assertEquals(1L, count("domains"));

        deleteCounter(domain.domainId());
        MysqlRegistrationAdminException integrityFailure = assertThrows(
                MysqlRegistrationAdminException.class,
                () -> registrationAdmin.ensureDomain(request("orders", 41, 10, 12)));
        assertEquals(SnowflakeErrorCode.MysqlRegistrationIntegrityFailed, integrityFailure.getErrorCode());
        assertEquals(0L, count("counters"));
    }

    @Test
    void counterFailureRollsBackDomainCreation() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_counter_insert
                    BEFORE INSERT ON counters
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced counter failure'
                    """);
        }

        MysqlRegistrationAdminException failure = assertThrows(
                MysqlRegistrationAdminException.class,
                () -> registrationAdmin.ensureDomain(request("orders", 41, 10, 12)));

        assertEquals(SnowflakeErrorCode.MysqlRegistrationOperationFailed, failure.getErrorCode());
        assertEquals(0L, count("domains"));
        assertEquals(0L, count("counters"));
    }

    @Test
    void equivalentGeneratorPolicyJsonReturnsOneGenerator() throws Exception {
        IdDomainInfo domain = registrationAdmin.ensureDomain(request("orders", 41, 10, 12));
        GeneratorRegistrationRequest request = generatorRequest("public-orders", 3);

        GeneratorConfig created = registrationAdmin.ensureGenerator(domain.domainId(), request);
        updatePolicyJson(created.generatorId(), """
                { "clockRollbackPolicy": "none", "maximumRetry": 3,
                  "idRunOutPolicy": "NONE" }
                """);
        GeneratorConfig repeated = registrationAdmin.ensureGenerator(domain.domainId(), request);

        assertTrue(created.generatorId() > 0);
        assertEquals(created, repeated);
        assertEquals(1L, count("generator_configs"));
    }

    @Test
    void unknownDomainAndPolicyConflictFailWithoutMutation() throws Exception {
        GeneratorRegistrationRequest initial = generatorRequest("public-orders", 1);
        MysqlRegistrationAdminException missingDomain = assertThrows(
                MysqlRegistrationAdminException.class,
                () -> registrationAdmin.ensureGenerator(Long.MAX_VALUE, initial));
        assertEquals(SnowflakeErrorCode.MysqlRegistrationDomainNotFound, missingDomain.getErrorCode());
        assertEquals(0L, count("generator_configs"));

        IdDomainInfo domain = registrationAdmin.ensureDomain(request("orders", 41, 10, 12));
        GeneratorConfig created = registrationAdmin.ensureGenerator(domain.domainId(), initial);
        MysqlRegistrationAdminException conflict = assertThrows(
                MysqlRegistrationAdminException.class,
                () -> registrationAdmin.ensureGenerator(
                        domain.domainId(), generatorRequest("public-orders", 2)));

        assertEquals(SnowflakeErrorCode.MysqlRegistrationConflict, conflict.getErrorCode());
        assertEquals(created, registrationAdmin.ensureGenerator(domain.domainId(), initial));
        assertEquals(1L, count("generator_configs"));
    }

    @Test
    void generatorInsertFailureLeavesNoGenerator() throws Exception {
        IdDomainInfo domain = registrationAdmin.ensureDomain(request("orders", 41, 10, 12));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_generator_insert
                    BEFORE INSERT ON generator_configs
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced generator failure'
                    """);
        }

        MysqlRegistrationAdminException failure = assertThrows(
                MysqlRegistrationAdminException.class,
                () -> registrationAdmin.ensureGenerator(
                        domain.domainId(), generatorRequest("public-orders", 1)));

        assertEquals(SnowflakeErrorCode.MysqlRegistrationOperationFailed, failure.getErrorCode());
        assertEquals(0L, count("generator_configs"));
    }

    private static DomainRegistrationRequest request(
            String domainName,
            int timeBit,
            int workerBit,
            int sequenceBit) {
        return new DomainRegistrationRequest(
                domainName,
                PrefixType.POSITIVE_SIGNAL,
                timeBit,
                workerBit,
                sequenceBit,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND);
    }

    private static GeneratorRegistrationRequest generatorRequest(String generatorName, int maximumRetry) {
        return new GeneratorRegistrationRequest(
                generatorName,
                new GeneratorPolicy(
                        maximumRetry,
                        IdRunOutPolicy.NONE,
                        ClockRollbackPolicy.NONE));
    }

    private static long count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long counterNextValue(long domainId) throws Exception {
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

    private static void deleteCounter(long domainId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM counters WHERE counter_ref = ?")) {
            statement.setString(1, CounterEntity.workerIdDomainRef(domainId));
            statement.executeUpdate();
        }
    }

    private static void updatePolicyJson(long generatorId, String policyJson) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE generator_configs SET policy = ? WHERE generator_id = ?")) {
            statement.setString(1, policyJson);
            statement.setLong(2, generatorId);
            statement.executeUpdate();
        }
    }
}
