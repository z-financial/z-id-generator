package com.zed.idgen.snowflake.core.manager.mysql.dao;

import org.apache.ibatis.session.SqlSession;

/**
 * One MyBatis SqlSession plus typed DAOs.
 *
 * Instances are intentionally not thread-safe. Open one session per transaction
 * or unit of work, then commit/rollback/close it in the caller.
 */
public class MysqlDaoSession implements AutoCloseable {
    private final SqlSession sqlSession;
    private final LeaseDao leaseDao;

    MysqlDaoSession(SqlSession sqlSession, int leaseRequestTimeoutSeconds) {
        this.sqlSession = sqlSession;
        this.leaseDao = new LeaseDao(sqlSession, leaseRequestTimeoutSeconds);
    }

    public DomainDao domainDao() {
        return sqlSession.getMapper(DomainDao.class);
    }

    public GeneratorConfigDao generatorConfigDao() {
        return sqlSession.getMapper(GeneratorConfigDao.class);
    }

    public WorkerDao workerDao() {
        return sqlSession.getMapper(WorkerDao.class);
    }

    public LeaseDao leaseDao() {
        return leaseDao;
    }

    public void commit() {
        sqlSession.commit();
    }

    public void rollback() {
        sqlSession.rollback();
    }

    @Override
    public void close() {
        sqlSession.close();
    }
}
