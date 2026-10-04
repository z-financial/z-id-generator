package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.component.IdCodec;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlWorkerRegistrationException;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoSession;
import com.zed.idgen.snowflake.core.manager.mysql.entity.CounterEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.WorkerEntity;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

/**
 * Atomically registers the next worker ID for one configured MySQL domain.
 */
public final class MysqlWorkerRegistrar implements WorkerRegistrar {
    private final MysqlDaoFactory daoFactory;
    private final String domainName;

    public MysqlWorkerRegistrar(MysqlDaoFactory daoFactory, String domainName) {
        if (daoFactory == null) {
            throw new InvalidConfigException("mysql dao factory must not be null");
        }
        if (domainName == null || domainName.isBlank()) {
            throw new InvalidConfigException("domain name must not be blank");
        }
        this.daoFactory = daoFactory;
        this.domainName = domainName.trim();
    }

    @Override
    public GeneratorProperties registerNext(long generatorId) {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }

        try {
            return registerInTransaction(generatorId);
        } catch (InvalidConfigException | MysqlWorkerRegistrationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw MysqlWorkerRegistrationException.operationFailed(generatorId, e);
        }
    }

    /**
     * Serializes allocation on the domain counter row, then inserts the worker and
     * advances the counter in one transaction. The session is committed and closed
     * before the registered properties are returned to the lease/start workflow.
     */
    private GeneratorProperties registerInTransaction(long generatorId) {
        GeneratorProperties registered;
        try (MysqlDaoSession session = daoFactory.openSession(false)) {
            try {
                GeneratorConfigEntity generatorEntity = session.generatorConfigDao().selectById(generatorId);
                if (generatorEntity == null) {
                    throw new InvalidConfigException("generator config not found by id: " + generatorId);
                }

                DomainEntity domainEntity = session.domainDao().findByName(domainName);
                validateGeneratorDomain(generatorId, generatorEntity, domainEntity, domainName);

                IdDomainInfo domain = MysqlRegistrationMapper.toDomainInfo(domainEntity);
                GeneratorConfig generatorConfig = MysqlRegistrationMapper.toGeneratorConfig(generatorEntity);
                CounterEntity counter = session.counterDao().findByRefForUpdate(
                        CounterEntity.workerIdDomainRef(domain.domainId()));
                if (counter == null) {
                    throw MysqlWorkerRegistrationException.missingCounter(domain.domainId());
                }

                long workerId = requireCounterValue(counter, generatorId);
                long maxWorker = new IdCodec(domain).getMaxWorker();
                if (workerId > maxWorker) {
                    throw MysqlWorkerRegistrationException.capacityExhausted(domain.domainId(), maxWorker);
                }

                WorkerEntity worker = new WorkerEntity(
                        null,
                        domain.domainId(),
                        generatorId,
                        workerId,
                        null,
                        null);
                if (session.workerDao().insert(worker) != 1) {
                    throw registrationFailure(generatorId, "worker insert affected an unexpected row count");
                }
                if (session.counterDao().advanceIfCurrent(counter.getCounterId(), workerId) != 1) {
                    throw registrationFailure(generatorId, "counter advance did not match the observed value");
                }

                registered = new GeneratorProperties(domain, generatorConfig, workerId, null);
                session.commit();
            } catch (RuntimeException e) {
                rollbackQuietly(session);
                throw e;
            }
        }
        return registered;
    }

    private static void validateGeneratorDomain(
            long generatorId,
            GeneratorConfigEntity generator,
            DomainEntity domain,
            String domainName) {
        if (domain == null) {
            throw new InvalidConfigException("domain not found by name: " + domainName);
        }
        if (generator.getDomainId() == null || !generator.getDomainId().equals(domain.getDomainId())) {
            throw new InvalidConfigException(
                    "generator domain mismatch from mysql, generator id: " + generatorId);
        }
    }

    private static long requireCounterValue(CounterEntity counter, long generatorId) {
        if (counter.getCounterId() == null || counter.getNextValue() == null || counter.getNextValue() < 0) {
            throw registrationFailure(generatorId, "worker counter contains an invalid value");
        }
        return counter.getNextValue();
    }

    private static MysqlWorkerRegistrationException registrationFailure(long generatorId, String reason) {
        return MysqlWorkerRegistrationException.operationFailed(
                generatorId,
                new IllegalStateException(reason));
    }

    private static void rollbackQuietly(MysqlDaoSession session) {
        try {
            session.rollback();
        } catch (RuntimeException ignored) {
            // TODO log
            // Preserve the original registration failure.
        }
    }
}
