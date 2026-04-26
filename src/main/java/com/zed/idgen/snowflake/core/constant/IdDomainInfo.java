package com.zed.idgen.snowflake.core.constant;

public record IdDomainInfo(
        Long domainId,
        String domainName,
        PrefixType prefixType,
        Integer timeBit,
        Integer workerBit,
        Integer sequenceBit,
        Long epochStartMs,
        TimeUnit timeUnit) {}
