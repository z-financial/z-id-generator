package com.zed.idgen.snowflake.core.model;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

public record IdDomainInfo(
        Long domainId,
        String domainName,
        PrefixType prefixType,
        Integer timeBit,
        Integer workerBit,
        Integer sequenceBit,
        Long epochStartMs,
        TimeUnit timeUnit) {

    public IdDomainInfo {
        if (domainId == null || domainId <= 0) {
            throw new InvalidConfigException("domain id must be positive");
        }
        if (domainName == null || domainName.isBlank()) {
            throw new InvalidConfigException("domain name must not be blank");
        }
    }
}
