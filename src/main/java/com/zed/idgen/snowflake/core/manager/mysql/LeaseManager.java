package com.zed.idgen.snowflake.core.manager.mysql;

import java.util.Optional;

public interface LeaseManager {

    Optional<LeaseHandle> tryAcquire(LeaseRequest request);

    boolean renew(LeaseHandle handle);

    boolean release(LeaseHandle handle);
}
