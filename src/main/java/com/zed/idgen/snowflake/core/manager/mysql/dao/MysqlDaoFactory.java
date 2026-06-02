package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlConfig;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.nio.file.Path;

/**
 * Small non-Spring MyBatis-Plus bootstrap for the MySQL DAO layer.
 *
 * The caller owns the config file path. Each MysqlDaoSession owns one
 * SqlSession, so transactions and SELECT ... FOR UPDATE are explicit and not
 * accidentally shared across threads.
 */
public class MysqlDaoFactory implements AutoCloseable {
    private final PooledDataSource dataSource;
    private final SqlSessionFactory sqlSessionFactory;
    private final int leaseRequestTimeoutSeconds;

    public MysqlDaoFactory(MysqlConfig mysqlConfig) {
        this.leaseRequestTimeoutSeconds = mysqlConfig.leaseRequestTimeoutSeconds();
        this.dataSource = new PooledDataSource(
                mysqlConfig.driverClassName(),
                mysqlConfig.jdbcUrl(),
                mysqlConfig.username(),
                mysqlConfig.password());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("mysql", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(mysqlConfig.defaultStatementTimeoutSeconds());
        registerDaos(configuration);
        this.sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
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
        dataSource.forceCloseAll();
    }

    private static void registerDaos(MybatisConfiguration configuration) {
        configuration.addMapper(DomainDao.class);
        configuration.addMapper(GeneratorConfigDao.class);
        configuration.addMapper(WorkerDao.class);
        configuration.addMapper(LeaseSqlMapper.class);
    }
}
