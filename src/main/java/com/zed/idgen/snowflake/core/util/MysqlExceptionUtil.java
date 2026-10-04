package com.zed.idgen.snowflake.core.util;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/** Classifies MySQL/JDBC failures without exposing driver exceptions to callers. */
public final class MysqlExceptionUtil {
    private static final int MYSQL_DUPLICATE_KEY = 1062;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_MAX_EXECUTION_TIMEOUT = 3024;
    private static final String MYSQL_QUERY_INTERRUPTED_SQL_STATE = "70100";
    private static final String JDBC_TIMEOUT_SQL_STATE = "S1T00";

    private MysqlExceptionUtil() {
    }

    public static boolean isDuplicateKey(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException
                    || "MySQLTimeoutException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlException.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                        || sqlException.getErrorCode() == MYSQL_MAX_EXECUTION_TIMEOUT
                        || MYSQL_QUERY_INTERRUPTED_SQL_STATE.equals(sqlState)
                        || JDBC_TIMEOUT_SQL_STATE.equals(sqlState)
                        || (sqlState != null && sqlState.startsWith("HYT"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
