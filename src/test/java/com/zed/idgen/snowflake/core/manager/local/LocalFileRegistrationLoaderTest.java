package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
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
        GeneratorProperties properties = loadFirst(loader,
                new GeneratorParam(null, "order-id-east"));

        assertEquals(100L, properties.idDomainInfo().domainId());
        assertEquals("orders", properties.idDomainInfo().domainName());
        assertEquals(11L, properties.generatorConfig().generatorId());
        assertEquals("order-id-east", properties.generatorConfig().generatorName());
        assertEquals(7L, properties.workerId());

        IdDomainInfo domainInfo = loader.getDomainInfo();
        assertEquals(100L, domainInfo.domainId());
        assertEquals("orders", domainInfo.domainName());

        GeneratorProperties byId = loadFirst(loader,
                new GeneratorParam(11L, null));
        assertEquals("order-id-east", byId.generatorConfig().generatorName());

        GeneratorProperties byIdAndName = loadFirst(loader, new GeneratorParam(11L, " order-id-east "));
        assertEquals(11L, byIdAndName.generatorConfig().generatorId());

        GeneratorProperties byIdWithBlankName = loadFirst(loader, new GeneratorParam(11L, " "));
        assertEquals("order-id-east", byIdWithBlankName.generatorConfig().generatorName());

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
        GeneratorProperties properties = loadFirst(loader, new GeneratorParam(null, "alpha"));

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
                        .loadGeneratorProperties(new GeneratorParam(11L, null)));

        assertTrue(ex.getMessage().contains("duplicated generator id"));
    }

    @Test
    void rejectsDuplicateWorkerIdFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-worker");
        writeGenerator(root, "duplicate-worker", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-worker", "12-7-beta.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-worker")
                        .loadGeneratorProperties(new GeneratorParam(null, "alpha")));

        assertTrue(ex.getMessage().contains("duplicated worker id"));
    }

    @Test
    void rejectsDuplicateGeneratorNameFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-name");
        writeGenerator(root, "duplicate-name", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-name", "12-8-alpha.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-name")
                        .loadGeneratorProperties(new GeneratorParam(null, "alpha")));

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
                        .loadGeneratorProperties(new GeneratorParam(null, "alpha")));

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
                        .loadGeneratorProperties(new GeneratorParam(null, "alpha")));

        assertTrue(ex.getMessage().contains("domain id mismatch"));
    }

    @Test
    void allowsSingleWorkerIdInLocalGeneratorConfig() throws IOException {
        Path root = prepareBase("single-worker");
        writeGenerator(root, "single-worker", "11-7-alpha.yml", generatorYaml("""
                workerId: 7
                """));

        GeneratorProperties properties = loadFirst(new LocalFileRegistrationLoader(root, "single-worker"),
                new GeneratorParam(null, "alpha"));

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
                        .loadGeneratorProperties(new GeneratorParam(null, "alpha")));

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
                        .loadGeneratorProperties(new GeneratorParam(null, "alpha")));
    }

    @Test
    void rejectsEmptyGeneratorParam() {
        assertThrows(InvalidConfigException.class, () -> new GeneratorParam(null, " "));
    }

    @Test
    void rejectsGeneratorParamIdAndNameMismatch() throws IOException {
        Path root = prepareBase("generator-param-mismatch");
        writeGenerator(root, "generator-param-mismatch", "11-7-alpha.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "generator-param-mismatch")
                        .loadGeneratorProperties(new GeneratorParam(11L, "beta")));

        assertTrue(ex.getMessage().contains("generator name mismatch"));
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

    private static GeneratorProperties loadFirst(LocalFileRegistrationLoader loader, GeneratorParam generatorParam) {
        return loader.loadGeneratorProperties(generatorParam).getFirst();
    }
}
