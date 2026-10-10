package com.zed.idgen.snowflake.core.component.provider;

public interface IdProvider {
    /**
     * Generates one ID.
     */
    long nextId();

    /**
     * Generates a complete batch of IDs.
     *
     * <p>A provider may reserve the batch in one operation, amortizing clock,
     * policy, and synchronization overhead across the returned IDs. Callers
     * should therefore prefer this method over repeated {@link #nextId()} calls
     * when they already need multiple IDs.</p>
     *
     * @param size number of IDs requested; implementation-specific capacity
     *             limits may apply
     * @return an array containing exactly {@code size} IDs
     * @throws IllegalArgumentException if {@code size} is not supported
     */
    long[] nextIds(int size);

}
