package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlConfig;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Small non-Spring MyBatis-Plus bootstrap for the MySQL DAO layer.
 *
 * The caller owns the config file path. Each MysqlDaoSession owns one
 * SqlSession, so transactions and SELECT ... FOR UPDATE are explicit and not
 * accidentally shared across threads.
 */
public class MysqlDaoFactory implements AutoCloseable {
    private final DataSource dataSource;
    /*
     * True only when this factory created the MyBatis pool itself. A
     * caller-supplied DataSource remains caller-owned and must be closed by the
     * application framework that created it (for example, Spring/Hikari).
     */
    private final boolean ownsDataSource;
    private final SqlSessionFactory sqlSessionFactory;
    private final int leaseRequestTimeoutSeconds;

    public MysqlDaoFactory(MysqlConfig mysqlConfig) {
        if (mysqlConfig == null) {
            throw new InvalidConfigException("mysql config must not be null");
        }
        this.leaseRequestTimeoutSeconds = mysqlConfig.leaseRequestTimeoutSeconds();
        this.dataSource = new PooledDataSource(
                mysqlConfig.driverClassName(),
                mysqlConfig.jdbcUrl(),
                mysqlConfig.username(),
                mysqlConfig.password());
        this.ownsDataSource = true;
        this.sqlSessionFactory = buildSqlSessionFactory(
                dataSource,
                mysqlConfig.defaultStatementTimeoutSeconds());
    }

    /**
     * Creates a DAO factory around an application-managed DataSource.
     *
     * <p>The supplied DataSource is borrowed, not owned. Closing this factory
     * does not close the DataSource. This is the preferred constructor for
     * Spring applications, where the container should own Hikari lifecycle,
     * metrics, credentials, and connection-pool configuration.</p>
     */
    public MysqlDaoFactory(
            DataSource dataSource,
            int defaultStatementTimeoutSeconds,
            int leaseRequestTimeoutSeconds) {
        if (dataSource == null) {
            throw new InvalidConfigException("mysql data source must not be null");
        }
        if (defaultStatementTimeoutSeconds <= 0) {
            throw new InvalidConfigException("mysql default statement timeout seconds must be positive");
        }
        if (leaseRequestTimeoutSeconds <= 0) {
            throw new InvalidConfigException("mysql lease request timeout seconds must be positive");
        }
        this.dataSource = dataSource;
        this.ownsDataSource = false;
        this.leaseRequestTimeoutSeconds = leaseRequestTimeoutSeconds;
        this.sqlSessionFactory = buildSqlSessionFactory(dataSource, defaultStatementTimeoutSeconds);
    }

    private static SqlSessionFactory buildSqlSessionFactory(
            DataSource dataSource,
            int defaultStatementTimeoutSeconds) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("mysql", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(defaultStatementTimeoutSeconds);
        registerDaos(configuration);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    public static MysqlDaoFactory fromConfigFile(Path configPath) {
        return new MysqlDaoFactory(MysqlConfig.load(configPath));
    }

    public MysqlDaoSession openSession() {
        return openSession(false);
    }

    public MysqlDaoSession openSession(boolean autoCommit) {
        return new MysqlDaoSession(sqlSessionFactory.openSession(autoCommit), leaseRequestTimeoutSeconds);
    }

    @Override
    public void close() {
        if (ownsDataSource) {
            ((PooledDataSource) dataSource).forceCloseAll();
        }
    }

    private static void registerDaos(MybatisConfiguration configuration) {
        configuration.addMapper(DomainDao.class);
        configuration.addMapper(CounterDao.class);
        configuration.addMapper(GeneratorConfigDao.class);
        configuration.addMapper(WorkerDao.class);
        configuration.addMapper(LeaseSqlMapper.class);
    }
}
