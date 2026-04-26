package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.constant.PrefixType;
import com.zed.idgen.snowflake.core.constant.TimeUnit;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.DomainParam;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        GeneratorInstanceProperties properties = loader.loadByName("order-id-east", new DomainParam(100L, "orders"));

        assertEquals(100L, properties.idDomainInfo().domainId());
        assertEquals("orders", properties.idDomainInfo().domainName());
        assertEquals(11L, properties.generatorInfo().generatorId());
        assertEquals("order-id-east", properties.generatorInfo().generatorName());
        assertEquals(100L, properties.generatorInfo().domainId());
        assertEquals("orders", properties.generatorInfo().domainName());
        assertEquals(List.of(7L), properties.workers());

        GeneratorInstanceProperties byId = loader.loadById(11L, new DomainParam(null, "orders"));
        assertEquals("order-id-east", byId.generatorInfo().generatorName());
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
                () -> new LocalFileRegistrationLoader(root, "duplicate-id").loadById(11L, null));

        assertTrue(ex.getMessage().contains("duplicated generator id"));
    }

    @Test
    void rejectsDuplicateWorkerIdFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-worker");
        writeGenerator(root, "duplicate-worker", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-worker", "12-7-beta.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-worker").loadByName("alpha", null));

        assertTrue(ex.getMessage().contains("duplicated worker id"));
    }

    @Test
    void rejectsDuplicateGeneratorNameFromFilenames() throws IOException {
        Path root = prepareBase("duplicate-name");
        writeGenerator(root, "duplicate-name", "11-7-alpha.yml", generatorYaml(""));
        writeGenerator(root, "duplicate-name", "12-8-alpha.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "duplicate-name").loadByName("alpha", null));

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
                () -> new LocalFileRegistrationLoader(root, "filename-mismatch").loadByName("alpha", null));

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
                () -> new LocalFileRegistrationLoader(root, "domain-mismatch").loadByName("alpha", null));

        assertTrue(ex.getMessage().contains("domain id mismatch"));
    }

    @Test
    void rejectsDomainParamMismatchWithLoadedDomainConfig() throws IOException {
        Path root = prepareBase("domain-param-mismatch");
        writeGenerator(root, "domain-param-mismatch", "11-7-alpha.yml", generatorYaml(""));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "domain-param-mismatch")
                        .loadByName("alpha", new DomainParam(101L, "orders")));

        assertTrue(ex.getMessage().contains("domain id mismatch"));
    }

    @Test
    void rejectsMultipleWorkersInLocalGeneratorConfig() throws IOException {
        Path root = prepareBase("multi-worker");
        writeGenerator(root, "multi-worker", "11-7-alpha.yml", generatorYaml("""
                workers:
                  - 7
                  - 8
                """));

        InvalidConfigException ex = assertThrows(InvalidConfigException.class,
                () -> new LocalFileRegistrationLoader(root, "multi-worker").loadByName("alpha", null));

        assertTrue(ex.getMessage().contains("zero or one worker"));
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
}
