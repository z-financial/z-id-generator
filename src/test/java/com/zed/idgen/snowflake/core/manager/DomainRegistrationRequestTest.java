package com.zed.idgen.snowflake.core.manager;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainRegistrationRequestTest {

    @Test
    void normalizesNameAndRejectsInvalidDefinitions() {
        DomainRegistrationRequest request = request("  orders  ", 41, 10, 12);
        assertEquals("orders", request.domainName());

        assertThrows(InvalidConfigException.class, () -> request("   ", 41, 10, 12));
        assertThrows(InvalidConfigException.class, () -> request("x".repeat(129), 41, 10, 12));
        assertThrows(InvalidConfigException.class, () -> request("orders", 40, 10, 12));
    }

    private static DomainRegistrationRequest request(
            String name,
            int timeBit,
            int workerBit,
            int sequenceBit) {
        return new DomainRegistrationRequest(
                name,
                PrefixType.POSITIVE_SIGNAL,
                timeBit,
                workerBit,
                sequenceBit,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND);
    }
}
