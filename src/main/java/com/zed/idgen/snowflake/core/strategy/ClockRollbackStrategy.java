package com.zed.idgen.snowflake.core.strategy;

public interface ClockRollbackStrategy {
    // return true to retry
    // return false to fail fast
    boolean afterClockRollback();

    static ClockRollbackStrategy none() {
        return () -> false;
    }
}
