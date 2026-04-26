package com.zed.idgen.snowflake.core.strategy;

import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;

public class StrategyExecutor {
    public static boolean idRunOutStrategy(IdRunOutPolicy idRunOutPolicy) {
        switch (idRunOutPolicy) {
            // TODO more strategies
            case NONE:
            default:
                return IdRunOutStrategy.none().afterRunOut();
        }
    }

    public static boolean clockRollbackStrategy(ClockRollbackPolicy clockRollbackPolicy) {
        switch (clockRollbackPolicy) {
            // TODO more strategies
            case NONE:
            default:
                return ClockRollbackStrategy.none().afterClockRollback();
        }
    }
}
