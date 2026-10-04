package com.zed.idgen.snowflake.core.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlExceptionUtilTest {

    @Test
    void identifiesDuplicateKeyByMysqlErrorCodeThroughCauseChain() {
        assertTrue(MysqlExceptionUtil.isDuplicateKey(
                new RuntimeException(new SQLException("duplicate", "23000", 1062))));
        assertFalse(MysqlExceptionUtil.isDuplicateKey(
                new SQLException("other constraint", "23000", 1048)));
    }

    @Test
    void identifiesDriverServerAndLockTimeouts() {
        assertTrue(MysqlExceptionUtil.isTimeout(new SQLTimeoutException()));
        assertTrue(MysqlExceptionUtil.isTimeout(new SQLException("lock timeout", "HY000", 1205)));
        assertTrue(MysqlExceptionUtil.isTimeout(new SQLException("query timeout", "HY000", 3024)));
        assertTrue(MysqlExceptionUtil.isTimeout(new SQLException("JDBC timeout", "HYT00", 0)));
        assertFalse(MysqlExceptionUtil.isTimeout(new SQLException("other failure", "HY000", 0)));
    }
}
