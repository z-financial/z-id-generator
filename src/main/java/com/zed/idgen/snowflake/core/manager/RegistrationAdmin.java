package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;

/**
 * Administrative API for idempotently provisioning immutable registration
 * definitions. Provisioning persists configuration only and does not start runtime
 * generators or acquire worker leases.
 */
public interface RegistrationAdmin {

    IdDomainInfo ensureDomain(DomainRegistrationRequest request);

    GeneratorConfig ensureGenerator(long domainId, GeneratorRegistrationRequest request);
}
