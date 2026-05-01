package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.GeneratorConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.IdDomainConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredGeneratorRuntimeManagerTest {

    @Test
    void startsGetsAndStopsConfiguredGenerator() {
        try (ConfiguredGeneratorRuntimeManager manager = new ConfiguredGeneratorRuntimeManager(config())) {
            IdGenerator generator = manager.startGeneratorInstance(new GeneratorParam(null, "order"));

            assertNotNull(generator);
            assertTrue(generator.getUid() > 0);
            assertSame(generator, manager.getGeneratorInstance(new GeneratorParam(11L, null)));
            assertSame(generator, manager.getGeneratorInstance(new GeneratorParam(11L, "order")));

            assertTrue(manager.stopGeneratorInstance(new GeneratorParam(null, "order")));
            assertNull(manager.getGeneratorInstance(new GeneratorParam(11L, null)));
            assertThrows(IdGenerationException.class, generator::getUid);
            assertFalse(manager.stopGeneratorInstance(new GeneratorParam(11L, null)));
        }
    }

    @Test
    void startIfNotExistsReturnsExistingConfiguredGenerator() {
        try (ConfiguredGeneratorRuntimeManager manager = new ConfiguredGeneratorRuntimeManager(config())) {
            IdGenerator first = manager.startGeneratorInstance(new GeneratorParam(11L, null));
            IdGenerator second = manager.startGeneratorInstanceIfNotExists(new GeneratorParam(null, "order"));

            assertSame(first, second);
        }
    }

    @Test
    void startSameConfiguredGeneratorTwiceThrowsDuplicateException() {
        try (ConfiguredGeneratorRuntimeManager manager = new ConfiguredGeneratorRuntimeManager(config())) {
            manager.startGeneratorInstance(new GeneratorParam(11L, null));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> manager.startGeneratorInstance(new GeneratorParam(null, "order")));

            assertTrue(ex.getMessage().contains("already running"));
        }
    }

    private static ConfigRuntimeConfig config() {
        return new ConfigRuntimeConfig(
                new IdDomainConfig(
                        100L,
                        "orders",
                        null,
                        41,
                        10,
                        12,
                        1_700_000_000_000L,
                        null
                ),
                List.of(new GeneratorConfig(11L, "order", null, null))
        );
    }
}
