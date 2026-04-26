package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.DomainLoader;
import com.zed.idgen.snowflake.core.manager.DomainParam;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.model.GeneratorInfo;
import com.zed.idgen.snowflake.core.model.GeneratorInstanceProperties;
import com.zed.idgen.snowflake.core.model.IdDomainInfo;
import com.zed.idgen.snowflake.core.util.PathUtil;
import com.zed.idgen.snowflake.core.util.StringUtil;
import com.zed.idgen.snowflake.core.util.YamlConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads local registration files from:
 * ./uid-register/[baseDir]/domain.yml
 * ./uid-register/[baseDir]/generator/[generator-id]-[worker-id]-[generator-name].yml
 * <p>
 * The filename is part of the local registration identity. Generator YAML may
 * omit ids and names, but any value it does set must match the filename and
 * the loaded domain config.
 */
public class LocalFileRegistrationLoader implements RegistrationLoader, DomainLoader {
    private static final String LOCAL_CONFIG_ROOT = "uid-register";
    private static final String DOMAIN_CONFIG_FILE = "domain.yml";
    private static final String GENERATOR_CONFIG_DIR = "generator";
    private static final String GENERATOR_CONFIG_SUFFIX = ".yml";

    private final Path basePath;
    private final Path domainConfigPath;
    private final Path generatorConfigDir;

    public LocalFileRegistrationLoader(String baseDir) {
        this(Path.of(LOCAL_CONFIG_ROOT), baseDir);
    }

    LocalFileRegistrationLoader(Path localConfigRoot, String baseDir) {
        Path rootPath = requireReadableDirectory(toAbsolutePath(localConfigRoot), "local registration root");
        this.basePath = resolveBasePath(rootPath, baseDir);
        this.domainConfigPath = basePath.resolve(DOMAIN_CONFIG_FILE);
        this.generatorConfigDir = requireReadableDirectory(basePath.resolve(GENERATOR_CONFIG_DIR), "generator config directory");
    }

    @Override
    public GeneratorInstanceProperties loadById(long generatorId, DomainParam domainParam) {
        if (generatorId < 0) {
            throw new InvalidConfigException("generator id must not be negative: " + generatorId);
        }
        IdDomainInfo domainInfo = loadDomain(domainParam);
        GeneratorConfigIndex index = loadGeneratorConfigIndex();
        GeneratorConfigFile configFile = index.byGeneratorId().get(generatorId);
        if (configFile == null) {
            throw new InvalidConfigException("generator config not found by id: " + generatorId);
        }
        return loadGenerator(configFile, domainInfo);
    }

    @Override
    public GeneratorInstanceProperties loadByName(String generatorName, DomainParam domainParam) {
        validateGeneratorName(generatorName, "requested generator name");
        IdDomainInfo domainInfo = loadDomain(domainParam);
        GeneratorConfigIndex index = loadGeneratorConfigIndex();
        GeneratorConfigFile configFile = index.byGeneratorName().get(generatorName);
        if (configFile == null) {
            throw new InvalidConfigException("generator config not found by name: " + generatorName);
        }
        return loadGenerator(configFile, domainInfo);
    }

    @Override
    public IdDomainInfo loadDomain(DomainParam domainParam) {
        requireReadableFile(domainConfigPath, "domain config file");
        IdDomainInfo domainInfo = YamlConfigLoader.load(domainConfigPath, IdDomainInfo.class);
        if (domainInfo == null) {
            throw new InvalidConfigException("domain config is empty: " + domainConfigPath);
        }
        verifyDomainParam(domainParam, domainInfo);
        return domainInfo;
    }

    private GeneratorInstanceProperties loadGenerator(GeneratorConfigFile configFile, IdDomainInfo domainInfo) {
        GeneratorConfig config = YamlConfigLoader.load(configFile.path(), GeneratorConfig.class);
        if (config == null) {
            throw new InvalidConfigException("generator config is empty: " + configFile.path());
        }
        if (config.generationPolicy() == null) {
            throw new InvalidConfigException("generation policy must not be null: " + configFile.fileName());
        }

        long generatorId = resolveGeneratorId(configFile, config);
        String generatorName = resolveGeneratorName(configFile, config);
        Long domainId = resolveDomainId(configFile, config, domainInfo);
        String domainName = resolveDomainName(configFile, config, domainInfo);
        List<Long> workers = resolveWorkers(configFile, config);

        return new GeneratorInstanceProperties(
                domainInfo,
                new GeneratorInfo(generatorId, generatorName, domainId, domainName, config.generationPolicy()),
                workers
        );
    }

    private GeneratorConfigIndex loadGeneratorConfigIndex() {
        Map<Long, GeneratorConfigFile> byGeneratorId = new HashMap<>();
        Map<Long, GeneratorConfigFile> byWorkerId = new HashMap<>();
        Map<String, GeneratorConfigFile> byGeneratorName = new HashMap<>();

        try (Stream<Path> paths = Files.list(generatorConfigDir)) {
            List<Path> entries = paths.toList();
            if (entries.isEmpty()) {
                throw new InvalidConfigException("generator config directory is empty: " + generatorConfigDir);
            }

            for (Path path : entries) {
                if (!Files.isRegularFile(path)) {
                    throw new InvalidConfigException("generator config path is not a regular file: " + path.getFileName());
                }
                GeneratorConfigFile configFile = parseGeneratorConfigFile(path);
                putUnique(byGeneratorId, configFile.generatorId(), configFile, "duplicated generator id");
                putUnique(byWorkerId, configFile.workerId(), configFile, "duplicated worker id");
                putUnique(byGeneratorName, configFile.generatorName(), configFile, "duplicated generator name");
            }
        } catch (IOException e) {
            throw new InvalidConfigException("failed to list generator config directory: " + generatorConfigDir, e);
        }

        return new GeneratorConfigIndex(byGeneratorId, byGeneratorName);
    }

    private static GeneratorConfigFile parseGeneratorConfigFile(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(GENERATOR_CONFIG_SUFFIX)) {
            throw new InvalidConfigException("generator config file must end with .yml: " + fileName);
        }

        String stem = fileName.substring(0, fileName.length() - GENERATOR_CONFIG_SUFFIX.length());
        if (stem.equals(".") || stem.equals("..")) {
            throw new InvalidConfigException("invalid generator config filename: " + fileName);
        }

        int firstSeparator = stem.indexOf('-');
        int secondSeparator = firstSeparator < 0 ? -1 : stem.indexOf('-', firstSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1 || secondSeparator == stem.length() - 1) {
            throw new InvalidConfigException("generator config filename must be [generator-id]-[worker-id]-[generator-name].yml: " + fileName);
        }

        String generatorIdPart = stem.substring(0, firstSeparator);
        String workerIdPart = stem.substring(firstSeparator + 1, secondSeparator);
        String generatorName = stem.substring(secondSeparator + 1);

        long generatorId = parseUnsignedLong(generatorIdPart, "generator id", fileName);
        long workerId = parseUnsignedLong(workerIdPart, "worker id", fileName);
        validateGeneratorName(generatorName, "generator config filename: " + fileName);

        return new GeneratorConfigFile(generatorId, workerId, generatorName, path, fileName);
    }

    private static long resolveGeneratorId(GeneratorConfigFile configFile, GeneratorConfig config) {
        if (config.generatorId() == null) {
            return configFile.generatorId();
        }
        if (config.generatorId() < 0) {
            throw new InvalidConfigException("generator id must not be negative: " + configFile.fileName());
        }
        if (config.generatorId() != configFile.generatorId()) {
            throw new InvalidConfigException("generator id mismatch, filename: " + configFile.generatorId()
                    + ", config: " + config.generatorId());
        }
        return config.generatorId();
    }

    private static String resolveGeneratorName(GeneratorConfigFile configFile, GeneratorConfig config) {
        if (config.generatorName() == null) {
            return configFile.generatorName();
        }
        if (!config.generatorName().equals(configFile.generatorName())) {
            throw new InvalidConfigException("generator name mismatch, filename: " + configFile.generatorName()
                    + ", config: " + config.generatorName());
        }
        return config.generatorName();
    }

    private static Long resolveDomainId(GeneratorConfigFile configFile, GeneratorConfig config, IdDomainInfo domainInfo) {
        if (config.domainId() == null) {
            return domainInfo.domainId();
        }
        if (!Objects.equals(config.domainId(), domainInfo.domainId())) {
            throw new InvalidConfigException("domain id mismatch, domain config: " + domainInfo.domainId()
                    + ", generator config: " + config.domainId() + ", file: " + configFile.fileName());
        }
        return config.domainId();
    }

    private static String resolveDomainName(GeneratorConfigFile configFile, GeneratorConfig config, IdDomainInfo domainInfo) {
        if (StringUtil.isEmpty(config.domainName())) {
            return domainInfo.domainName();
        }
        if (!config.domainName().equals(domainInfo.domainName())) {
            throw new InvalidConfigException("domain name mismatch, domain config: " + domainInfo.domainName()
                    + ", generator config: " + config.domainName() + ", file: " + configFile.fileName());
        }
        return config.domainName();
    }

    private static List<Long> resolveWorkers(GeneratorConfigFile configFile, GeneratorConfig config) {
        if (config.workers() == null) {
            return List.of(configFile.workerId());
        }
        if (config.workers().size() != 1 || config.workers().getFirst() == null) {
            throw new InvalidConfigException("local generator config must contain zero or one worker: " + configFile.fileName());
        }
        long workerId = config.workers().getFirst();
        if (workerId < 0) {
            throw new InvalidConfigException("worker id must not be negative: " + configFile.fileName());
        }
        if (workerId != configFile.workerId()) {
            throw new InvalidConfigException("worker id mismatch, filename: " + configFile.workerId()
                    + ", config: " + workerId);
        }
        return List.of(workerId);
    }

    private static void verifyDomainParam(DomainParam domainParam, IdDomainInfo domainInfo) {
        if (domainParam == null) {
            return;
        }
        if (domainParam.domainId() != null && !Objects.equals(domainParam.domainId(), domainInfo.domainId())) {
            throw new InvalidConfigException("domain id mismatch, expected: " + domainParam.domainId()
                    + ", config: " + domainInfo.domainId());
        }
        if (domainParam.domainName() != null && !domainParam.domainName().equals(domainInfo.domainName())) {
            throw new InvalidConfigException("domain name mismatch, expected: " + domainParam.domainName()
                    + ", config: " + domainInfo.domainName());
        }
    }

    private static <T> void putUnique(Map<T, GeneratorConfigFile> map,
                                      T key,
                                      GeneratorConfigFile configFile,
                                      String errorMessage) {
        GeneratorConfigFile existing = map.putIfAbsent(key, configFile);
        if (existing != null) {
            throw new InvalidConfigException(errorMessage + ": " + key + ", files: "
                    + existing.fileName() + ", " + configFile.fileName());
        }
    }

    private static Path resolveBasePath(Path rootPath, String baseDir) {
        Path baseSegment;
        Path resolvedPath;
        try {
            baseSegment = PathUtil.requireSafeSinglePathSegment(baseDir);
            resolvedPath = PathUtil.resolveSafeChildSegment(rootPath, baseSegment);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException("invalid local registration baseDir: " + baseDir, e);
        }
        return requireReadableDirectory(resolvedPath, "local registration base directory");
    }

    private static long parseUnsignedLong(String value, String fieldName, String fileName) {
        if (StringUtil.isEmpty(value)) {
            throw new InvalidConfigException(fieldName + " must not be blank in generator config filename: " + fileName);
        }
        if (value.charAt(0) == '+' || value.charAt(0) == '-') {
            throw new InvalidConfigException(fieldName + " must not have a leading sign in generator config filename: " + fileName);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new InvalidConfigException(fieldName + " parse failed, filename: " + fileName, e);
        }
    }

    private static void validateGeneratorName(String generatorName, String target) {
        try {
            PathUtil.requireSafeSinglePathSegment(generatorName);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException("invalid generator name: " + target, e);
        }
    }

    private static Path toAbsolutePath(Path path) {
        if (path == null) {
            throw new InvalidConfigException("local registration root must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path requireReadableDirectory(Path path, String label) {
        if (!Files.exists(path)) {
            throw new InvalidConfigException(label + " does not exist: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw new InvalidConfigException(label + " is not a directory: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new InvalidConfigException(label + " is not readable: " + path);
        }
        return path;
    }

    private static void requireReadableFile(Path path, String label) {
        if (!Files.exists(path)) {
            throw new InvalidConfigException(label + " does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new InvalidConfigException(label + " is not a file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new InvalidConfigException(label + " is not readable: " + path);
        }
    }

    private record GeneratorConfigFile(long generatorId, long workerId, String generatorName, Path path, String fileName) {
    }

    private record GeneratorConfigIndex(Map<Long, GeneratorConfigFile> byGeneratorId,
                                        Map<String, GeneratorConfigFile> byGeneratorName) {
    }
}
