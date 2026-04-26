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

    private static final int ID_BIT_COUNT = 64;

    public IdDomainInfo {
        if (domainId == null || domainId <= 0) {
            throw new InvalidConfigException("domain id must be positive");
        }
        if (domainName == null || domainName.isBlank()) {
            throw new InvalidConfigException("domain name must not be blank");
        }
        if (prefixType == null) {
            throw new InvalidConfigException("prefix type must not be null");
        }
        if (timeBit == null || timeBit <= 0) {
            throw new InvalidConfigException("time bit must be positive");
        }
        if (workerBit == null || workerBit <= 0) {
            throw new InvalidConfigException("worker bit must be positive");
        }
        if (sequenceBit == null || sequenceBit <= 0) {
            throw new InvalidConfigException("sequence bit must be positive");
        }
        if (epochStartMs == null || epochStartMs < 0) {
            throw new InvalidConfigException("epoch start ms must not be negative");
        }
        if (timeUnit == null) {
            throw new InvalidConfigException("time unit must not be null");
        }

        int prefixBit = prefixType == PrefixType.POSITIVE_SIGNAL ? 1 : 0;
        long totalBit = (long) timeBit + workerBit + sequenceBit + prefixBit;
        if (totalBit != ID_BIT_COUNT) {
            throw new InvalidConfigException(String.format(
                    "total bit count mismatch, prefixType: %s, timeBit: %d, workerBit: %d, sequenceBit: %d",
                    prefixType, timeBit, workerBit, sequenceBit));
        }
    }
}
