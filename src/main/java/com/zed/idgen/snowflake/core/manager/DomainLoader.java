package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.IdDomainInfo;

public interface DomainLoader {
    IdDomainInfo loadDomain(DomainParam domainParam);
}
