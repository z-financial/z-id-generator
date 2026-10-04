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

    @Override
    public GeneratorConfig ensureGenerator(long domainId, GeneratorRegistrationRequest request) {
        throw new UnsupportedOperationException("generator registration is implemented by ZIG-4");
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
