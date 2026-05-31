package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileRegistrationLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsValidGeneratorByNameAndFillsMissingFieldsFromFilenameAndDomain() throws IOException {
        Path root = prepareBase("local-a");
        writeGenerator(root, "local-a", "11-7-order-id-east.yml", generatorYaml(""));

        LocalFileRegistrationLoader loader = new LocalFileRegistrationLoader(root, "local-a");
        long resolvedGeneratorId = loader.resolveGeneratorId(" order-id-east ");
        GeneratorProperties properties = loadFirst(loader, resolvedGeneratorId);

        assertEquals(100L, properties.idDomainInfo().domainId());
        assertEquals("orders", properties.idDomainInfo().domainName());
        assertEquals(11L, properties.generatorConfig().generatorId());
        assertEquals("order-id-east", properties.generatorConfig().generatorName());
        assertEquals(7L, properties.workerId());

        IdDomainInfo domainInfo = loader.getDomainInfo();
        assertEquals(100L, domainInfo.domainId());
        assertEquals("orders", domainInfo.domainName());

        GeneratorProperties byId = loadFirst(loader, 11L);
        assertEquals("order-id-east", byId.generatorConfig().generatorName());
        assertEquals(11L, resolvedGeneratorId);

        GeneratorInstanceProperties instanceProperties = GeneratorInstanceProperties.from(properties);
        assertEquals(11L, instanceProperties.generatorId());
        assertEquals("order-id-east", instanceProperties.generatorName());
        assertEquals(7L, instanceProperties.workerId());
    }

    @Test
    void keepsDomainInfoStableAfterDomainFileChanges() throws IOException {
        Path root = prepareBase("stable-domain");
        writeGenerator(root, "stable-domain", "11-7-alpha.yml", generatorYaml(""));

        LocalFileRegistrationLoader loader = new LocalFileRegistrationLoader(root, "stable-domain");
        Files.writeString(root.resolve("stable-domain").resolve("domain.yml"), """
                domainId: 200
                domainName: changed
                prefixType: POSITIVE_SIGNAL
                timeBit: 41
                workerBit: 10
                sequenceBit: 12
                epochStartMs: 1700000000000
                timeUnit: MILLISECOND
                """);

        IdDomainInfo domainInfo = loader.getDomainInfo();
        GeneratorProperties properties = loadFirst(loader, loader.resolveGeneratorId("alpha"));

        assertEquals(100L, domainInfo.domainId());
        assertEquals("orders", domainInfo.domainName());
        assertEquals(100L, properties.idDomainInfo().domainId());
        assertEquals("orders", properties.idDomainInfo().domainName());
    }

    @Test
    void rejectsInvalidBaseDirPathTraversal() throws IOException {
        Path root = tempDir.resolve("uid-register");
        Files.createDirectories(root);

        assertThrows(InvalidConfigException.class, () -> new LocalFileRegistrationLoader(root, ".."));
        assertThrows(InvalidConfigException.class, () -> new LocalFileRegistrationLoader(root, "a/b"));
        assertThrows(InvalidConfigException.class, () -> new LocalFileRegistrationLoader(root, "."));
    }

    @Test
    void rejectsDuplicateGeneratorIdFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-id");
        writeGenerator(root, "duplicate-id", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-id", "11-8-beta.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-id")
                        .loadGeneratorProperties(11L));

        assertTrue(ex.getMessage().contains("duplicated generator id"));
    }

    @Test
    void rejectsDuplicateWorkerIdFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-worker");
        writeGenerator(root, "duplicate-worker", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-worker", "12-7-beta.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-worker")
                        .resolveGeneratorId("alpha"));

        assertTrue(ex.getMessage().contains("duplicated worker id"));
    }

    @Test
    void rejectsDuplicateGeneratorNameFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-name");
        writeGenerator(root, "duplicate-name", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-name", "12-8-alpha.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-name")
                        .resolveGeneratorId("alpha"));

        assertTrue(ex.getMessage().contains("duplicated generator name"));
    }

    @Test
    void rejectsGeneratorYamlMismatchWithFilename() throws IOException {
        Path root = prepareBase("filename-mismatch");
        writeGenerator(root, "filename-mismatch", "11-7-alpha.yml", generatorYaml("""
                generatorId: 12
                generatorName: beta
                """));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "filename-mismatch")
                        .loadGeneratorProperties(11L));

        assertTrue(ex.getMessage().contains("generator id mismatch"));
    }

    @Test
    void rejectsGeneratorYamlMismatchWithDomainConfig() throws IOException {
        Path root = prepareBase("domain-mismatch");
        writeGenerator(root, "domain-mismatch", "11-7-alpha.yml", generatorYaml("""
                domainId: 101
                domainName: other
                """));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "domain-mismatch")
                        .loadGeneratorProperties(11L));

        assertTrue(ex.getMessage().contains("domain id mismatch"));
    }

    @Test
    void allowsSingleWorkerIdInLocalGeneratorConfig() throws IOException {
        Path root = prepareBase("single-worker");
        writeGenerator(root, "single-worker", "11-7-alpha.yml", generatorYaml("""
                workerId: 7
                """));

        GeneratorProperties properties = loadFirst(new LocalFileRegistrationLoader(root, "single-worker"), 11L);

        assertEquals(7L, properties.workerId());
    }

    @Test
    void rejectsWorkerIdMismatchWithFilename() throws IOException {
        Path root = prepareBase("worker-mismatch");
        writeGenerator(root, "worker-mismatch", "11-7-alpha.yml", generatorYaml("""
                workerId: 8
                """));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "worker-mismatch")
                        .loadGeneratorProperties(11L));

        assertTrue(ex.getMessage().contains("worker id mismatch"));
    }

    @Test
    void rejectsWorkersArrayInLocalGeneratorConfig() throws IOException {
        Path root = prepareBase("workers-array");
        writeGenerator(root, "workers-array", "11-7-alpha.yml", generatorYaml("""
                workers:
                  - 7
                """));

        assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "workers-array")
                        .loadGeneratorProperties(11L));
    }

    @Test
    void rejectsEmptyGeneratorNameResolution() {
        Path root = prepareBaseUnchecked("empty-name");
        assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "empty-name").resolveGeneratorId(" "));
    }

    @Test
    void rejectsMissingGeneratorNameResolution() throws IOException {
        Path root = prepareBase("missing-name");
        writeGenerator(root, "missing-name", "11-7-alpha.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "missing-name").resolveGeneratorId("beta"));

        assertTrue(ex.getMessage().contains("generator config not found by name"));
    }

    @Test
    void rejectsMissingDomainIdentity() {
        assertThrows(InvalidConfigException.class, () -> new IdDomainInfo(
                null,
                "orders",
                PrefixType.POSITIVE_SIGNAL,
                41,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND
        ));

        assertThrows(InvalidConfigException.class, () -> new IdDomainInfo(
                100L,
                " ",
                PrefixType.POSITIVE_SIGNAL,
                41,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND
        ));
    }

    @Test
    void rejectsInvalidDomainLayout() {
        assertThrows(InvalidConfigException.class, () -> new IdDomainInfo(
                100L,
                "orders",
                null,
                41,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND
        ));

        assertThrows(InvalidConfigException.class, () -> new IdDomainInfo(
                100L,
                "orders",
                PrefixType.POSITIVE_SIGNAL,
                0,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND
        ));

        assertThrows(InvalidConfigException.class, () -> new IdDomainInfo(
                100L,
                "orders",
                PrefixType.POSITIVE_SIGNAL,
                40,
                10,
                12,
                1_700_000_000_000L,
                TimeUnit.MILLISECOND
        ));

        assertThrows(InvalidConfigException.class, () -> new IdDomainInfo(
                100L,
                "orders",
                PrefixType.POSITIVE_SIGNAL,
                41,
                10,
                12,
                -1L,
                TimeUnit.MILLISECOND
        ));
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

    private String generatorYaml(String extraFields) {
        return extraFields + """
                generationPolicy:
                  maximumRetry: 0
                  idRunOutPolicy: NONE
                  clockRollbackPolicy: NONE
                """;
    }

    private Path prepareBaseUnchecked(String baseDir) {
        try {
            return prepareBase(baseDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static GeneratorProperties loadFirst(LocalFileRegistrationLoader loader, long generatorId) {
        return loader.loadGeneratorProperties(generatorId).getFirst();
    }
}
