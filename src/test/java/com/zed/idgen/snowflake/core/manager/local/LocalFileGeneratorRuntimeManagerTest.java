package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.constant.ClockRollbackPolicy;
import com.zed.idgen.snowflake.core.constant.IdRunOutPolicy;
import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.IdGenerationException;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
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
            GeneratorProperties properties = manager.getGeneratorProperties(new GeneratorParam(null, "order-id"));

            assertEquals(11L, properties.generatorConfig().generatorId());
            assertEquals("order-id", properties.generatorConfig().generatorName());
            assertEquals(List.of(7L), properties.workerIds());
        }
    }

    @Test
    void startsGeneratorInstanceSuccessfully() throws IOException {
        Path root = prepareBase("runtime-start");
        writeGenerator(root, "runtime-start", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-start")) {
            IdGenerator generator = manager.startGeneratorInstance(new GeneratorParam(11L, null));

            assertNotNull(generator);
            assertTrue(generator.getUid() > 0);
        }
    }

    @Test
    void getsExistingGeneratorByIdAndName() throws IOException {
        Path root = prepareBase("runtime-get");
        writeGenerator(root, "runtime-get", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-get")) {
            IdGenerator generator = manager.startGeneratorInstance(new GeneratorParam(11L, null));

            assertSame(generator, manager.getGeneratorInstance(new GeneratorParam(11L, null)));
            assertSame(generator, manager.getGeneratorInstance(new GeneratorParam(null, "order-id")));
            assertSame(generator, manager.getGeneratorInstance(new GeneratorParam(11L, "order-id")));
            assertNull(manager.getGeneratorInstance(new GeneratorParam(11L, "other")));
        }
    }

    @Test
    void startSameGeneratorTwiceThrowsDuplicateException() throws IOException {
        Path root = prepareBase("runtime-duplicate-start");
        writeGenerator(root, "runtime-duplicate-start", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-duplicate-start")) {
            manager.startGeneratorInstance(new GeneratorParam(11L, null));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> manager.startGeneratorInstance(new GeneratorParam(null, "order-id")));

            assertTrue(ex.getMessage().contains("already running"));
        }
    }

    @Test
    void startIfNotExistsReturnsExistingInstance() throws IOException {
        Path root = prepareBase("runtime-start-existing");
        writeGenerator(root, "runtime-start-existing", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-start-existing")) {
            IdGenerator first = manager.startGeneratorInstance(new GeneratorParam(11L, null));
            IdGenerator second = manager.startGeneratorInstanceIfNotExists(new GeneratorParam(null, "order-id"));

            assertSame(first, second);
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
            manager.startGeneratorInstance(new GeneratorParam(11L, null));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> manager.startGeneratorInstance(new GeneratorParam(12L, null)));

            assertTrue(ex.getMessage().contains("worker id is already running"));
        }
    }

    @Test
    void stopExistingAndMissingGenerator() throws IOException {
        Path root = prepareBase("runtime-stop");
        writeGenerator(root, "runtime-stop", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-stop")) {
            manager.startGeneratorInstance(new GeneratorParam(11L, null));

            assertTrue(manager.stopGeneratorInstance(new GeneratorParam(null, "order-id")));
            assertNull(manager.getGeneratorInstance(new GeneratorParam(11L, null)));
            assertFalse(manager.stopGeneratorInstance(new GeneratorParam(99L, null)));
        }
    }

    @Test
    void stoppedGeneratorCannotGenerateIds() throws IOException {
        Path root = prepareBase("runtime-stopped-generator");
        writeGenerator(root, "runtime-stopped-generator", "11-7-order-id.yml", generatorYaml());

        try (LocalFileGeneratorRuntimeManager manager = new LocalFileGeneratorRuntimeManager(root, "runtime-stopped-generator")) {
            IdGenerator generator = manager.startGeneratorInstance(new GeneratorParam(11L, null));
            manager.stopGeneratorInstance(new GeneratorParam(11L, null));

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
                List.of(workerId),
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
        private final Map<Long, GeneratorProperties> byId = new HashMap<>();
        private final Map<String, GeneratorProperties> byName = new HashMap<>();

        private MapRegistrationLoader(GeneratorProperties... properties) {
            for (GeneratorProperties property : properties) {
                byId.put(property.generatorConfig().generatorId(), property);
                byName.put(property.generatorConfig().generatorName(), property);
            }
        }

        @Override
        public IdDomainInfo loadDomain() {
            return domainInfo();
        }

        @Override
        public GeneratorProperties loadGenerator(GeneratorParam generatorParam) {
            if (generatorParam.generatorId() != null) {
                GeneratorProperties properties = byId.get(generatorParam.generatorId());
                if (properties == null) {
                    throw new InvalidConfigException("generator not found: " + generatorParam.generatorId());
                }
                if (generatorParam.generatorName() != null
                        && !generatorParam.generatorName().equals(properties.generatorConfig().generatorName())) {
                    throw new InvalidConfigException("generator name mismatch");
                }
                return properties;
            }

            GeneratorProperties properties = byName.get(generatorParam.generatorName());
            if (properties == null) {
                throw new InvalidConfigException("generator not found: " + generatorParam.generatorName());
            }
            return properties;
        }
    }
}
