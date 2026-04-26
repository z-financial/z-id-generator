package com.zed.idgen.snowflake.core.strategy;

public interface IdRunOutStrategy {
    // return true to retry
    // return false to give up
    boolean afterRunOut();

    static IdRunOutStrategy none() {
        return () -> false;
    }
}
