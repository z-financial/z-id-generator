package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;

public record DomainParam(Long domainId, String domainName) {
    public DomainParam {
        if (domainId != null && domainId < 0) {
            throw new InvalidConfigException("domain id must not be negative");
        }
        if (domainName != null) {
            domainName = domainName.trim();
            if (domainName.isBlank()) {
                domainName = null;
            }
        }
    }
}
