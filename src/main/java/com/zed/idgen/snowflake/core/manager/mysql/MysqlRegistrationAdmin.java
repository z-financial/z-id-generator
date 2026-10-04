package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.MysqlRegistrationAdminException;
import com.zed.idgen.snowflake.core.manager.DomainRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.GeneratorRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.RegistrationAdmin;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoSession;
import com.zed.idgen.snowflake.core.manager.mysql.entity.CounterEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import com.zed.idgen.snowflake.core.util.MysqlExceptionUtil;

/**
 * MySQL-backed registration administrator. It retains only the DAO factory; every
 * lookup and mutation owns an independent session so the instance is safe to share.
 */
public final class MysqlRegistrationAdmin implements RegistrationAdmin {
    private static final String DOMAIN_COUNTER_NAME = "Domain worker IDs";

    private final MysqlDaoFactory daoFactory;

    public MysqlRegistrationAdmin(MysqlDaoFactory daoFactory) {
        if (daoFactory == null) {
            throw new InvalidConfigException("mysql dao factory must not be null");
        }
        this.daoFactory = daoFactory;
    }

    /**
     * Returns an equivalent persisted domain or atomically creates the domain and
     * its canonical worker-ID counter. A duplicate insert is treated only as a race:
     * the failed transaction closes before the winning definition is reloaded and
     * compared.
     */
    @Override
    public IdDomainInfo ensureDomain(DomainRegistrationRequest request) {
        if (request == null) {
            throw new InvalidConfigException("domain registration request must not be null");
        }

        try {
            IdDomainInfo existing = findAndVerifyDomain(request);
            if (existing != null) {
                return existing;
            }

            IdDomainInfo created = createDomainAndCounter(request);
            if (created != null) {
                return created;
            }

            // A duplicate-key loser must reload through a fresh session after rollback/close.
            IdDomainInfo winner = findAndVerifyDomain(request);
            if (winner == null) {
                throw MysqlRegistrationAdminException.operationFailed("reload raced domain");
            }
            return winner;
        } catch (InvalidConfigException | MysqlRegistrationAdminException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translateFailure("ensure domain", e);
        }
    }

    /**
     * Returns an equivalent persisted generator or creates it under an existing
     * domain. Creation commits only the definition; duplicate races are reloaded
     * and compared after the losing transaction closes.
     */
    @Override
    public GeneratorConfig ensureGenerator(long domainId, GeneratorRegistrationRequest request) {
        if (domainId < 0) {
            throw new InvalidConfigException("domain id must not be negative");
        }
        if (request == null) {
            throw new InvalidConfigException("generator registration request must not be null");
        }

        try {
            GeneratorConfig existing = findAndVerifyGenerator(domainId, request);
            if (existing != null) {
                return existing;
            }

            GeneratorConfig created = createGenerator(domainId, request);
            if (created != null) {
                return created;
            }

            // The duplicate-key loser reloads only after its failed transaction has closed.
            GeneratorConfig winner = findAndVerifyGenerator(domainId, request);
            if (winner == null) {
                throw MysqlRegistrationAdminException.operationFailed("reload raced generator");
            }
            return winner;
        } catch (InvalidConfigException | MysqlRegistrationAdminException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translateFailure("ensure generator", e);
        }
    }

    private GeneratorConfig findAndVerifyGenerator(
            long domainId,
            GeneratorRegistrationRequest request) {
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            GeneratorConfigEntity entity = session.generatorConfigDao()
                    .findByName(domainId, request.generatorName());
            if (entity == null) {
                return null;
            }

            GeneratorConfig generator = toAuthoritativeGenerator(entity, domainId);
            // Compare deserialized values so JSON order and formatting cannot affect idempotency.
            if (!generator.generationPolicy().equals(request.generationPolicy())) {
                throw MysqlRegistrationAdminException.generatorConflict(
                        domainId, request.generatorName());
            }
            return generator;
        }
    }

    /**
     * Verifies the parent domain and inserts one generator in the same short
     * transaction. A null result means the natural-key insert lost a race.
     */
    private GeneratorConfig createGenerator(
            long domainId,
            GeneratorRegistrationRequest request) {
        try (MysqlDaoSession session = daoFactory.openSession(false)) {
            try {
                // The schema has no foreign key, so the transaction must establish parent existence.
                if (session.domainDao().findById(domainId) == null) {
                    throw MysqlRegistrationAdminException.domainNotFound(domainId);
                }

                GeneratorConfigEntity entity = MysqlRegistrationMapper.toGeneratorConfigEntity(
                        domainId, request);
                try {
                    if (session.generatorConfigDao().insert(entity) != 1) {
                        throw MysqlRegistrationAdminException.operationFailed("insert generator");
                    }
                } catch (RuntimeException e) {
                    if (MysqlExceptionUtil.isDuplicateKey(e)) {
                        rollbackQuietly(session);
                        return null;
                    }
                    throw translateFailure("insert generator", e);
                }

                GeneratorConfig generator = toAuthoritativeGenerator(entity, domainId);
                session.commit();
                return generator;
            } catch (RuntimeException e) {
                rollbackQuietly(session);
                if (e instanceof InvalidConfigException || e instanceof MysqlRegistrationAdminException) {
                    throw e;
                }
                throw translateFailure("create generator", e);
            }
        }
    }

    private static GeneratorConfig toAuthoritativeGenerator(
            GeneratorConfigEntity entity,
            long expectedDomainId) {
        if (entity.getGeneratorId() == null || entity.getGeneratorId() <= 0) {
            throw MysqlRegistrationAdminException.operationFailed("read generated generator id");
        }
        if (entity.getDomainId() == null || entity.getDomainId() != expectedDomainId) {
            throw MysqlRegistrationAdminException.operationFailed("verify generator domain");
        }
        return MysqlRegistrationMapper.toGeneratorConfig(entity);
    }

    private IdDomainInfo findAndVerifyDomain(DomainRegistrationRequest request) {
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            DomainEntity entity = session.domainDao().findByName(request.domainName());
            if (entity == null) {
                return null;
            }

            IdDomainInfo domain = MysqlRegistrationMapper.toDomainInfo(entity);
            if (!hasSameDefinition(domain, request)) {
                throw MysqlRegistrationAdminException.domainConflict(request.domainName());
            }
            String counterRef = CounterEntity.workerIdDomainRef(domain.domainId());
            if (session.counterDao().findByRef(counterRef) == null) {
                throw MysqlRegistrationAdminException.missingDomainCounter(domain.domainId());
            }
            return domain;
        }
    }

    /**
     * Inserts both rows in one short transaction. A null return means the domain
     * insert lost the natural-key race; all other failures are translated here.
     */
    private IdDomainInfo createDomainAndCounter(DomainRegistrationRequest request) {
        try (MysqlDaoSession session = daoFactory.openSession(false)) {
            DomainEntity entity = MysqlRegistrationMapper.toDomainEntity(request);
            try {
                if (session.domainDao().insert(entity) != 1) {
                    throw MysqlRegistrationAdminException.operationFailed("insert domain");
                }
            } catch (RuntimeException e) {
                rollbackQuietly(session);
                if (MysqlExceptionUtil.isDuplicateKey(e)) {
                    return null;
                }
                throw translateFailure("insert domain", e);
            }

            try {
                Long domainId = entity.getDomainId();
                if (domainId == null || domainId <= 0) {
                    throw MysqlRegistrationAdminException.operationFailed("read generated domain id");
                }

                CounterEntity counter = new CounterEntity(
                        null,
                        CounterEntity.workerIdDomainRef(domainId),
                        DOMAIN_COUNTER_NAME,
                        0L,
                        null,
                        null);
                if (session.counterDao().insert(counter) != 1) {
                    throw MysqlRegistrationAdminException.operationFailed("insert domain counter");
                }

                IdDomainInfo domain = MysqlRegistrationMapper.toDomainInfo(entity);
                session.commit();
                return domain;
            } catch (RuntimeException e) {
                rollbackQuietly(session);
                if (e instanceof InvalidConfigException || e instanceof MysqlRegistrationAdminException) {
                    throw e;
                }
                throw translateFailure("create domain and counter", e);
            }
        }
    }

    private static boolean hasSameDefinition(
            IdDomainInfo domain,
            DomainRegistrationRequest request) {
        return domain.domainName().equals(request.domainName())
                && domain.prefixType() == request.prefixType()
                && domain.timeBit() == request.timeBit()
                && domain.workerBit() == request.workerBit()
                && domain.sequenceBit() == request.sequenceBit()
                && domain.epochStartMs() == request.epochStartMs()
                && domain.timeUnit() == request.timeUnit();
    }

    private static MysqlRegistrationAdminException translateFailure(
            String operation,
            RuntimeException failure) {
        return MysqlExceptionUtil.isTimeout(failure)
                ? MysqlRegistrationAdminException.operationTimedOut(operation, failure)
                : MysqlRegistrationAdminException.operationFailed(operation, failure);
    }

    private static void rollbackQuietly(MysqlDaoSession session) {
        try {
            session.rollback();
        } catch (RuntimeException ignored) {
            // TODO log
            // Preserve the registration failure that caused rollback.
        }
    }
}
