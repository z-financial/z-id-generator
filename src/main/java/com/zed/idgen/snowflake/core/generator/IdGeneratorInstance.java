package com.zed.idgen.snowflake.core.generator;

import com.zed.idgen.snowflake.core.IdGenerator;

public interface IdGeneratorInstance extends IdGenerator {
    /**
     * Returns whether this runtime instance can generate IDs right now.
     * Implementations with extra guards, such as a lease deadline, must include
     * those guards here instead of exposing only the raw lifecycle state.
     */
    boolean canGenerate();

    /**
     * Starts this instance. Returns true only when the call changes the state
     * from INIT to RUNNING; returns false when it is already running.
     */
    boolean start();

    /**
     * Stops this instance. Returns true only when the call changes the state to
     * STOPPED; returns false when it was already stopped.
     */
    boolean stop();

    /**
     * A manager-local monotonic id for one runtime incarnation. It is used for
     * comparing runtime objects without relying on mutable object equality.
     */
    long getInstanceId();
}
