package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.constant.IdDomainInfo;

public interface DomainLoader {
    IdDomainInfo loadDomainById(long id);
    IdDomainInfo loadDomainByName(String name);
}
