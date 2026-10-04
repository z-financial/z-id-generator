package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.DomainRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.GeneratorRegistrationRequest;
import com.zed.idgen.snowflake.core.manager.mysql.entity.DomainEntity;
import com.zed.idgen.snowflake.core.manager.mysql.entity.GeneratorConfigEntity;
import com.zed.idgen.snowflake.core.manager.mysql.enums.DomainPrefixType;
import com.zed.idgen.snowflake.core.manager.mysql.enums.DomainTimeUnit;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import com.zed.idgen.snowflake.core.util.GeneratorPolicyJsonUtil;

final class MysqlRegistrationMapper {
    private MysqlRegistrationMapper() {
    }

    static IdDomainInfo toDomainInfo(DomainEntity entity) {
        return new IdDomainInfo(
                entity.getDomainId(),
                entity.getDomainName(),
                toPrefixType(entity.getPrefixType()),
                entity.getTimeBit(),
                entity.getWorkerBit(),
                entity.getSequenceBit(),
                entity.getEpochStartMs(),
                toTimeUnit(entity.getTimeUnit())
        );
    }

    static DomainEntity toDomainEntity(DomainRegistrationRequest request) {
        return new DomainEntity(
                null,
                request.domainName(),
                toDomainPrefixType(request.prefixType()),
                request.timeBit(),
                request.workerBit(),
                request.sequenceBit(),
                request.epochStartMs(),
                toDomainTimeUnit(request.timeUnit()),
                null,
                null);
    }

    static GeneratorConfig toGeneratorConfig(GeneratorConfigEntity entity) {
        return new GeneratorConfig(
                entity.getGeneratorId(),
                entity.getGeneratorName(),
                GeneratorPolicyJsonUtil.fromJson(entity.getPolicy())
        );
    }

    static GeneratorConfigEntity toGeneratorConfigEntity(
            long domainId,
            GeneratorRegistrationRequest request) {
        return new GeneratorConfigEntity(
                null,
                domainId,
                request.generatorName(),
                GeneratorPolicyJsonUtil.toJson(request.generationPolicy()),
                null,
                null);
    }

    private static PrefixType toPrefixType(DomainPrefixType prefixType) {
        if (prefixType == null) {
            throw new InvalidConfigException("domain prefix type must not be null");
        }
        return switch (prefixType) {
            case POSITIVE_SIGNAL -> PrefixType.POSITIVE_SIGNAL;
            case NONE -> PrefixType.NONE;
        };
    }

    private static TimeUnit toTimeUnit(DomainTimeUnit timeUnit) {
        if (timeUnit == null) {
            throw new InvalidConfigException("domain time unit must not be null");
        }
        return switch (timeUnit) {
            case MILLISECOND -> TimeUnit.MILLISECOND;
            case SECOND -> TimeUnit.SECOND;
        };
    }

    private static DomainPrefixType toDomainPrefixType(PrefixType prefixType) {
        return switch (prefixType) {
            case POSITIVE_SIGNAL -> DomainPrefixType.POSITIVE_SIGNAL;
            case NONE -> DomainPrefixType.NONE;
        };
    }

    private static DomainTimeUnit toDomainTimeUnit(TimeUnit timeUnit) {
        return switch (timeUnit) {
            case MILLISECOND -> DomainTimeUnit.MILLISECOND;
            case SECOND -> DomainTimeUnit.SECOND;
        };
    }
}
