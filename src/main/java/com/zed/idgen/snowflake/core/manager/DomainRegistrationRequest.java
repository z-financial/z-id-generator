package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

/** Immutable domain definition supplied to {@link RegistrationAdmin}. */
public record DomainRegistrationRequest(
        String domainName,
        PrefixType prefixType,
        int timeBit,
        int workerBit,
        int sequenceBit,
        long epochStartMs,
        TimeUnit timeUnit) {

    private static final int MAX_NAME_LENGTH = 128;

    public DomainRegistrationRequest {
        if (domainName == null) {
            throw new InvalidConfigException("domain name must not be null");
        }
        domainName = domainName.trim();
        if (domainName.isEmpty()) {
            throw new InvalidConfigException("domain name must not be blank");
        }
        if (domainName.length() > MAX_NAME_LENGTH) {
            throw new InvalidConfigException("domain name must not exceed 128 characters");
        }

        // Reuse the authoritative model invariants before any persistence work begins.
        new IdDomainInfo(
                1L,
                domainName,
                prefixType,
                timeBit,
                workerBit,
                sequenceBit,
                epochStartMs,
                timeUnit);
    }
}
