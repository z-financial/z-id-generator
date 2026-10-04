package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoSession;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.WorkerEntity;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RegistrationLoader backed by MySQL registration tables.
 * <p>
 * Domain information is lazily loaded and cached as a volatile immutable
 * snapshot. The volatile write safely publishes the loaded IdDomainInfo to
 * other threads. We intentionally do not synchronize initialization: duplicate
 * DB loads are acceptable because domain rows are treated as immutable and the
 * conversion from DB row to IdDomainInfo is deterministic.
 */
public final class MysqlRegistrationLoader implements RegistrationLoader {
    private final MysqlDaoFactory daoFactory;
    private final String domainName;
    private volatile IdDomainInfo domainInfo;

    public MysqlRegistrationLoader(MysqlDaoFactory daoFactory, String domainName) {
        if (daoFactory == null) {
            throw new InvalidConfigException("mysql dao factory must not be null");
        }
        this.daoFactory = daoFactory;
        this.domainName = requireText(domainName, "domain name");
    }

    @Override
    public IdDomainInfo getDomainInfo() {
        IdDomainInfo current = domainInfo;
        if (current != null) {
            return current;
        }

        IdDomainInfo loaded = loadDomainInfo();
        domainInfo = loaded;
        return loaded;
    }

    @Override
    public long resolveGeneratorId(String generatorName) {
        String normalizedName = requireText(generatorName, "generator name");
        long domainId = getDomainInfo().domainId();
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            Long generatorId = session.generatorConfigDao().findIdByName(domainId, normalizedName);
            if (generatorId == null) {
                throw new InvalidConfigException("generator config not found by name: " + normalizedName);
            }
            return generatorId;
        }
    }

    @Override
    public List<GeneratorProperties> loadGeneratorProperties(long generatorId) {
        validateGeneratorId(generatorId);
        IdDomainInfo domain = getDomainInfo();
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            GeneratorConfigEntity generatorEntity = session.generatorConfigDao()
                    .findById(domain.domainId(), generatorId);
            if (generatorEntity == null) {
                throw new InvalidConfigException("generator config not found by id: " + generatorId);
            }
            validateGeneratorEntity(domain, generatorId, generatorEntity);

            List<WorkerEntity> workers = session.workerDao().findByGenerator(domain.domainId(), generatorId);
            if (workers == null || workers.isEmpty()) {
                throw new InvalidConfigException("worker list must not be empty, generator id: " + generatorId);
            }

            GeneratorConfig generatorConfig = MysqlRegistrationMapper.toGeneratorConfig(generatorEntity);
            return toGeneratorProperties(domain, generatorConfig, workers);
        }
    }

    private IdDomainInfo loadDomainInfo() {
        try (MysqlDaoSession session = daoFactory.openSession(true)) {
            DomainEntity domainEntity = session.domainDao().findByName(domainName);
            if (domainEntity == null) {
                throw new InvalidConfigException("domain not found by name: " + domainName);
            }
            return MysqlRegistrationMapper.toDomainInfo(domainEntity);
        }
    }

    private static List<GeneratorProperties> toGeneratorProperties(IdDomainInfo domain,
                                                                   GeneratorConfig generatorConfig,
                                                                   List<WorkerEntity> workers) {
        List<GeneratorProperties> properties = new ArrayList<>(workers.size());
        Set<Long> workerIds = new HashSet<>();
        for (WorkerEntity worker : workers) {
            validateWorkerEntity(domain, generatorConfig.generatorId(), worker);
            if (!workerIds.add(worker.getWorkerId())) {
                throw new InvalidConfigException("duplicated worker id from mysql: " + worker.getWorkerId());
            }
            properties.add(new GeneratorProperties(domain, generatorConfig, worker.getWorkerId(), null));
        }
        return List.copyOf(properties);
    }


    private static void validateGeneratorEntity(IdDomainInfo domain,
                                                long requestedGeneratorId,
                                                GeneratorConfigEntity entity) {
        if (entity.getGeneratorId() == null || entity.getGeneratorId() != requestedGeneratorId) {
            throw new InvalidConfigException("generator id mismatch from mysql, requested: " + requestedGeneratorId);
        }
        if (!domain.domainId().equals(entity.getDomainId())) {
            throw new InvalidConfigException("generator domain mismatch from mysql, generator id: " + requestedGeneratorId);
        }
        requireText(entity.getGeneratorName(), "generator name");
    }

    private static void validateWorkerEntity(IdDomainInfo domain, long generatorId, WorkerEntity worker) {
        if (worker == null) {
            throw new InvalidConfigException("worker row must not be null");
        }
        if (!domain.domainId().equals(worker.getDomainId())) {
            throw new InvalidConfigException("worker domain mismatch from mysql, generator id: " + generatorId);
        }
        if (worker.getGeneratorId() == null || worker.getGeneratorId() != generatorId) {
            throw new InvalidConfigException("worker generator mismatch from mysql, generator id: " + generatorId);
        }
        if (worker.getWorkerId() == null) {
            throw new InvalidConfigException("worker id must not be null, generator id: " + generatorId);
        }
    }

    private static void validateGeneratorId(long generatorId) {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
