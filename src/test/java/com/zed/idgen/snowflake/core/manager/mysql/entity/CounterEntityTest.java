package com.zed.idgen.snowflake.core.manager.mysql.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterEntityTest {

    @Test
    void buildsCanonicalWorkerIdDomainReference() {
        assertEquals("worker-id:domain:42", CounterEntity.workerIdDomainRef(42L));
    }
}
