package com.zed.idgen.snowflake.core.manager.config;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.GeneratorConfig;
import com.zed.idgen.snowflake.core.manager.config.ConfigRuntimeConfig.IdDomainConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            long generatorId = manager.resolveGeneratorId("order");
            IdGenerator generator = manager.startGeneratorInstance(generatorId);

            assertNotNull(generator);
            assertTrue(generator.getUid() > 0);
            assertSame(generator, manager.getGeneratorInstance(11L, 0L));

            assertTrue(manager.stopGeneratorInstance(generatorId, 0L));
            assertNull(manager.getGeneratorInstance(11L, 0L));
            assertThrows(IdGenerationException.class, generator::getUid);
            assertFalse(manager.stopGeneratorInstance(11L, 0L));
        }
    }

    @Test
    void startsMultipleWorkersUnderSameGeneratorConfig() {
        try (ConfiguredGeneratorRuntimeManager manager = new ConfiguredGeneratorRuntimeManager(multiWorkerConfig())) {
            IdGenerator worker1 = manager.startGeneratorInstance(11L, 1L);
            IdGenerator worker2 = manager.startGeneratorInstance(manager.resolveGeneratorId("order"), 2L);

            Map<Long, IdGenerator> generators = manager.getGeneratorInstances(11L);

            assertEquals(2, generators.size());
            assertSame(worker1, generators.get(1L));
            assertSame(worker2, generators.get(2L));

            assertTrue(manager.stopGeneratorInstance(11L, 1L));
            assertNull(manager.getGeneratorInstance(11L, 1L));
            assertSame(worker2, manager.getGeneratorInstance(11L, 2L));
        }
    }

    @Test
    void startSameConfiguredGeneratorTwiceThrowsDuplicateException() {
        try (ConfiguredGeneratorRuntimeManager manager = new ConfiguredGeneratorRuntimeManager(config())) {
            manager.startGeneratorInstance(11L, 0L);

            IdGenerationException ex = assertThrows(IdGenerationException.class,
                    () -> manager.startGeneratorInstance(manager.resolveGeneratorId("order"), 0L));

            assertEquals(SnowflakeErrorCode.GeneratorAlreadyRunning, ex.getErrorCode());
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

    private static ConfigRuntimeConfig multiWorkerConfig() {
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
                List.of(
                        new GeneratorConfig(11L, "order", null, 1L),
                        new GeneratorConfig(11L, "order", null, 2L)
                )
        );
    }
}
