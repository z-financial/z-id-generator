package com.zed.idgen.snowflake.core.manager;

/**
 * Source-specific state created before a runtime generator is built.
 * <p>
 * The base manager treats the attachment as opaque. A distributed manager can
 * store temporary creation resources here, such as a lease handle acquired
 * before constructing a leased generator.
 */
public record RuntimeCreateContext(Object attachment) {
    private static final RuntimeCreateContext EMPTY = new RuntimeCreateContext(null);

    public static RuntimeCreateContext empty() {
        return EMPTY;
    }
}
