package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.manager.AbstractGeneratorRuntimeManager;

/**
 * Runtime manager backed by an immutable config object. It delegates all
 * config validation/defaulting to ConfigRegistrationLoader and reuses the
 * shared runtime lifecycle/indexing from AbstractGeneratorRuntimeManager.
 */
public class ConfiguredGeneratorRuntimeManager extends AbstractGeneratorRuntimeManager {
    public ConfiguredGeneratorRuntimeManager(ConfigRuntimeConfig config) {
        super(new ConfigRegistrationLoader(config));
    }
}
