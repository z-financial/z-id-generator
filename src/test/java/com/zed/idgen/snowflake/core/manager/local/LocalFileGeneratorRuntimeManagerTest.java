package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorPolicy;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileGeneratorRuntimeManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsDomainThroughRuntimeManager() throws IOException {
        Path root = prepareBase("runtime-domain");
        writeGenerator(root, "runtime-domain", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-domain")) {
            IdDomainInfo domainInfo = manager.getDomainInfo();

            assertEquals(100L, domainInfo.domainId());
            assertEquals("orders", domainInfo.domainName());
        }
    }

    @Test
    void loadsGeneratorPropertiesThroughRuntimeManager() throws IOException {
        Path root = prepareBase("runtime-properties");
        writeGenerator(root, "runtime-properties", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-properties")) {
            GeneratorProperties properties = manager.getGeneratorProperties(manager.resolveGeneratorId("order-id")).getFirst();

            assertEquals(11L, properties.generatorConfig().generatorId());
            assertEquals("order-id", properties.generatorConfig().generatorName());
            assertEquals(7L, properties.workerId());
        }
    }

    @Test
    void startsGeneratorInstanceSuccessfully() throws IOException {
        Path root = prepareBase("runtime-start");
        writeGenerator(root, "runtime-start", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-start")) {
            IdGenerator generator = manager.startGeneratorInstance(11L);

            assertNotNull(generator);
            assertTrue(generator.getUid() > 0);
        }
    }

    @Test
    void getsExistingGeneratorById() throws IOException {
        Path root = prepareBase("runtime-get");
        writeGenerator(root, "runtime-get", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-get")) {
            IdGenerator generator = manager.startGeneratorInstance(11L);

            assertSame(generator, manager.getGeneratorInstance(11L, 7L));
            assertNull(manager.getGeneratorInstance(99L, 7L));
        }
    }

    @Test
    void startSameGeneratorTwiceThrowsDuplicateException() throws IOException {
        Path root = prepareBase("runtime-duplicate-start");
        writeGenerator(root, "runtime-duplicate-start", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-duplicate-start")) {
            manager.startGeneratorInstance(11L);

            IdGenerationException ex = assertThrows(IdGenerationException.class,
                    () -> manager.startGeneratorInstance(manager.resolveGeneratorId("order-id")));

            assertEquals(SnowflakeErrorCode.NoAvailableWorker, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("no available worker"));
        }
    }

    @Test
    void startsMultipleWorkersUnderSameGeneratorConfig() throws IOException {
        Path basePath = Files.createDirectories(tempDir.resolve("runtime-multi-worker"));
        MapRegistrationLoader loader = new MapRegistrationLoader(
                properties(11L, "order-id", 7L),
                properties(11L, "order-id", 8L)
        );

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(loader, basePath)) {
            IdGenerator worker7 = manager.startGeneratorInstance(11L, 7L);
            IdGenerator worker8 = manager.startGeneratorInstance(manager.resolveGeneratorId("order-id"), 8L);

            Map<Long, IdGenerator> generators = manager.getGeneratorInstances(11L);
            assertEquals(2, generators.size());
            assertSame(worker7, generators.get(7L));
            assertSame(worker8, generators.get(8L));

            assertTrue(manager.stopGeneratorInstance(11L, 7L));
            assertNull(manager.getGeneratorInstance(11L, 7L));
            assertSame(worker8, manager.getGeneratorInstance(11L, 8L));
        }
    }

    @Test
    void duplicateWorkerIdWithDifferentGeneratorThrows() throws IOException {
        Path basePath = Files.createDirectories(tempDir.resolve("runtime-worker-duplicate"));
        MapRegistrationLoader loader = new MapRegistrationLoader(
                properties(11L, "alpha", 7L),
                properties(12L, "beta", 7L)
        );

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(loader, basePath)) {
            manager.startGeneratorInstance(11L);

            IdGenerationException ex = assertThrows(IdGenerationException.class,
                    () -> manager.startGeneratorInstance(12L));

            assertEquals(SnowflakeErrorCode.NoAvailableWorker, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("no available worker"));
        }
    }

    @Test
    void stopExistingAndMissingGenerator() throws IOException {
        Path root = prepareBase("runtime-stop");
        writeGenerator(root, "runtime-stop", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-stop")) {
            manager.startGeneratorInstance(11L);

            assertTrue(manager.stopGeneratorInstance(manager.resolveGeneratorId("order-id"), 7L));
            assertNull(manager.getGeneratorInstance(11L, 7L));
            assertFalse(manager.stopGeneratorInstance(99L, 7L));
        }
    }

    @Test
    void stoppedGeneratorCannotGenerateIds() throws IOException {
        Path root = prepareBase("runtime-stopped-generator");
        writeGenerator(root, "runtime-stopped-generator", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-stopped-generator")) {
            IdGenerator generator = manager.startGeneratorInstance(11L);
            manager.stopGeneratorInstance(11L, 7L);

            assertThrows(IdGenerationException.class, generator::getUid);
        }
    }

    @Test
    void createsRunningPlaceholderOnInitialization() throws IOException {
        Path root = prepareBase("runtime-placeholder");
        writeGenerator(root, "runtime-placeholder", "11-7-order-id.yml", generatorYaml());
        Path placeholder = root.resolve("runtime-placeholder").resolve("running.placeholder");

        try (LocalFileGeneratorRuntimeManager ignored = new LocalFileGeneratorRuntimeManager(root, "runtime-placeholder")) {
            assertTrue(Files.isRegularFile(placeholder));
        }
    }

    @Test
    void initializationFailsIfRunningPlaceholderAlreadyExists() throws IOException {
        Path root = prepareBase("runtime-placeholder-exists");
        writeGenerator(root, "runtime-placeholder-exists", "11-7-order-id.yml", generatorYaml());
        Files.writeString(root.resolve("runtime-placeholder-exists").resolve("running.placeholder"), "already running");

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileGeneratorRuntimeManager(root, "runtime-placeholder-exists"));

        assertTrue(ex.getMessage().contains("placeholder already exists"));
    }

    @Test
    void closeDeletesRunningPlaceholder() throws IOException {
        Path root = prepareBase("runtime-placeholder-delete");
        writeGenerator(root, "runtime-placeholder-delete", "11-7-order-id.yml", generatorYaml());
        Path placeholder = root.resolve("runtime-placeholder-delete").resolve("running.placeholder");

        LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-placeholder-delete");
        assertTrue(Files.exists(placeholder));

        manager.close();

        assertFalse(Files.exists(placeholder));
    }

    private Path prepareBase(String baseDir) throws IOException {
        Path root = tempDir.resolve("uid-register");
        Path base = root.resolve(baseDir);
        Files.createDirectories(base.resolve("generator"));
        Files.writeString(base.resolve("domain.yml"), """
                domainId: 100
                domainName: orders
                prefixType: POSITIVE_SIGNAL
                timeBit: 41
                workerBit: 10
                sequenceBit: 12
                epochStartMs: 1700000000000
                timeUnit: MILLISECOND
                """);
        return root;
    }

    private void writeGenerator(Path root, String baseDir, String fileName, String content) throws IOException {
        Files.writeString(root.resolve(baseDir).resolve("generator").resolve(fileName), content);
    }

    private String generatorYaml() {
        return """
                generationPolicy:
                  maximumRetry: 0
                  idRunOutPolicy: NONE
                  clockRollbackPolicy: NONE
                """;
    }

    private static GeneratorProperties properties(long generatorId, String generatorName, long workerId) {
        return new GeneratorProperties(
                domainInfo(),
                new GeneratorConfig(generatorId, generatorName, policy()),
                workerId,
                null
        );
    }

    private static IdDomainInfo domainInfo() {
        return new IdDomainInfo(
                100L,
                "orders",
                PrefixType.POSITIVE_SIGNAL,
                41,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND
        );
    }

    private static GeneratorPolicy policy() {
        return new GeneratorPolicy(0, IdRunOutPolicy.NONE, ClockRollbackPolicy.NONE);
    }

    private static final class MapRegistrationLoader implements RegistrationLoader {
        private final Map<Long, List<GeneratorProperties>> byId = new HashMap<>();
        private final Map<String, Long> byName = new HashMap<>();

        private MapRegistrationLoader(GeneratorProperties... properties) {
            for (GeneratorProperties property : properties) {
                byId.computeIfAbsent(property.generatorConfig().generatorId(), ignored -> new java.util.ArrayList<>())
                        .add(property);
                byName.put(property.generatorConfig().generatorName(), property.generatorConfig().generatorId());
            }
        }

        @Override
        public IdDomainInfo getDomainInfo() {
            return domainInfo();
        }

        @Override
        public long resolveGeneratorId(String generatorName) {
            Long generatorId = byName.get(generatorName);
            if (generatorId == null) {
                throw new InvalidConfigException("generator not found: " + generatorName);
            }
            return generatorId;
        }

        @Override
        public List<GeneratorProperties> loadGeneratorProperties(long generatorId) {
            List<GeneratorProperties> properties = byId.get(generatorId);
            if (properties == null) {
                throw new InvalidConfigException("generator not found: " + generatorId);
            }
            return properties;
        }
    }
}
