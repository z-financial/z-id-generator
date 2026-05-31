package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.GeneratorParam;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.model.GeneratorConfig;
import com.zed.idgen.snowflake.core.model.GeneratorProperties;
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
 * omit ids, names, and workerId, but any value it does set must match the
 * filename and the loaded domain config.
 * <p>
 * domain.yml is read once during loader construction because domain info
 * defines the UID bit layout and decode rules. Generator files are still read
 * on each loadGeneratorProperties call so local generator changes can be picked up
 * without changing the domain snapshot.
 */
class LocalFileRegistrationLoader implements RegistrationLoader {
    private static final String LOCAL_CONFIG_ROOT = "uid-register";
    private static final String DOMAIN_CONFIG_FILE = "domain.yml";
    private static final String GENERATOR_CONFIG_DIR = "generator";
    private static final String GENERATOR_CONFIG_SUFFIX = ".yml";

    private final Path basePath;
    private final Path domainConfigPath;
    private final Path generatorConfigDir;
    private final IdDomainInfo domainInfo;

    /**
     * Creates a loader for ./uid-register/[baseDir].
     * The baseDir value must be one safe path segment and must already exist.
     */
    LocalFileRegistrationLoader(String baseDir) {
        this(Path.of(LOCAL_CONFIG_ROOT), baseDir);
    }

    LocalFileRegistrationLoader(Path localConfigRoot, String baseDir) {
        Path rootPath = requireReadableDirectory(toAbsolutePath(localConfigRoot), "local registration root");
        this.basePath = resolveBasePath(rootPath, baseDir);
        this.domainConfigPath = basePath.resolve(DOMAIN_CONFIG_FILE);
        this.domainInfo = readDomainInfo(domainConfigPath);
        this.generatorConfigDir = requireReadableDirectory(basePath.resolve(GENERATOR_CONFIG_DIR), "generator config directory");
    }

    Path basePath() {
        return basePath;
    }

    /**
     * Loads registered worker bindings by GeneratorParam. Local file mode maps
     * one generator file to one worker binding, so the returned list currently
     * contains exactly one item.
     */
    @Override
    public List<GeneratorProperties> loadGeneratorProperties(GeneratorParam generatorParam) {
        if (generatorParam == null) {
            throw new InvalidConfigException("generator param must not be null");
        }
        GeneratorConfigIndex index = loadGeneratorConfigIndex();
        GeneratorConfigFile configFile = findGeneratorConfig(generatorParam, index);
        return List.of(loadGeneratorProperties(configFile, domainInfo));
    }

    /**
     * Selects one indexed generator config. If both id and name are provided,
     * the id locates the file and the name must match the filename identity.
     */
    private static GeneratorConfigFile findGeneratorConfig(GeneratorParam generatorParam, GeneratorConfigIndex index) {
        Long generatorId = generatorParam.generatorId();
        String generatorName = generatorParam.generatorName();
        if (generatorName != null) {
            validateGeneratorName(generatorName, "requested generator name");
        }

        if (generatorId != null) {
            GeneratorConfigFile configFile = index.byGeneratorId().get(generatorId);
            if (configFile == null) {
                throw new InvalidConfigException("generator config not found by id: " + generatorId);
            }
            if (generatorName != null && !generatorName.equals(configFile.generatorName())) {
                throw new InvalidConfigException("generator name mismatch, requested: " + generatorName
                        + ", filename: " + configFile.generatorName());
            }
            return configFile;
        }

        GeneratorConfigFile configFile = index.byGeneratorName().get(generatorName);
        if (configFile == null) {
            throw new InvalidConfigException("generator config not found by name: " + generatorName);
        }
        return configFile;
    }

    /**
     * Returns the startup domain snapshot. This intentionally does not reread
     * domain.yml, because changing bit layout/epoch/prefix while a process is
     * running can make generated UIDs duplicated or undecodable.
     */
    @Override
    public IdDomainInfo getDomainInfo() {
        return domainInfo;
    }

    /**
     * Reads and validates domain.yml once while constructing the loader.
     */
    private static IdDomainInfo readDomainInfo(Path domainConfigPath) {
        requireReadableFile(domainConfigPath, "domain config file");
        IdDomainInfo domainInfo = YamlConfigLoader.load(domainConfigPath, IdDomainInfo.class);
        if (domainInfo == null) {
            throw new InvalidConfigException("domain config is empty: " + domainConfigPath);
        }
        return domainInfo;
    }

    /**
     * Loads one generator YAML as registration data. Filename values provide
     * omitted generator identity, and any domain identity in YAML must match
     * domain.yml.
     */
    private GeneratorProperties loadGeneratorProperties(GeneratorConfigFile configFile, IdDomainInfo domainInfo) {
        LocalFileGeneratorConfig localConfig = YamlConfigLoader.load(configFile.path(), LocalFileGeneratorConfig.class);
        if (localConfig == null) {
            throw new InvalidConfigException("generator config is empty: " + configFile.path());
        }
        if (localConfig.generationPolicy() == null) {
            throw new InvalidConfigException("generation policy must not be null: " + configFile.fileName());
        }

        long generatorId = resolveGeneratorId(configFile, localConfig);
        String generatorName = resolveGeneratorName(configFile, localConfig);
        validateDomainId(configFile, localConfig, domainInfo);
        validateDomainName(configFile, localConfig, domainInfo);
        long workerId = resolveWorker(configFile, localConfig);

        return new GeneratorProperties(
                domainInfo,
                new GeneratorConfig(generatorId, generatorName, localConfig.generationPolicy()),
                workerId,
                null
        );
    }

    /**
     * Scans all generator files first so duplicate generator ids, worker ids,
     * and generator names are rejected before returning a matched config.
     */
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

    /**
     * Parses [generator-id]-[worker-id]-[generator-name].yml by locating the
     * first two separators, allowing generator names to contain hyphens.
     */
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

    /**
     * Generator id may be omitted in YAML, but any explicit value must match
     * the id encoded in the filename.
     */
    private static long resolveGeneratorId(GeneratorConfigFile configFile, LocalFileGeneratorConfig config) {
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

    /**
     * Generator name may be omitted in YAML, but any explicit value must match
     * the name encoded in the filename.
     */
    private static String resolveGeneratorName(GeneratorConfigFile configFile, LocalFileGeneratorConfig config) {
        if (config.generatorName() == null) {
            return configFile.generatorName();
        }
        if (!config.generatorName().equals(configFile.generatorName())) {
            throw new InvalidConfigException("generator name mismatch, filename: " + configFile.generatorName()
                    + ", config: " + config.generatorName());
        }
        return config.generatorName();
    }

    /**
     * Generator YAML may omit domain id; if present, it must match domain.yml
     * because local registration has exactly one domain config per baseDir.
     */
    private static void validateDomainId(GeneratorConfigFile configFile, LocalFileGeneratorConfig config, IdDomainInfo domainInfo) {
        if (config.domainId() == null) {
            return;
        }
        if (!Objects.equals(config.domainId(), domainInfo.domainId())) {
            throw new InvalidConfigException("domain id mismatch, domain config: " + domainInfo.domainId()
                    + ", generator config: " + config.domainId() + ", file: " + configFile.fileName());
        }
    }

    /**
     * Generator YAML may omit domain name; if present, it must match domain.yml
     * because local registration has exactly one domain config per baseDir.
     */
    private static void validateDomainName(GeneratorConfigFile configFile, LocalFileGeneratorConfig config, IdDomainInfo domainInfo) {
        if (StringUtil.isEmpty(config.domainName())) {
            return;
        }
        if (!config.domainName().equals(domainInfo.domainName())) {
            throw new InvalidConfigException("domain name mismatch, domain config: " + domainInfo.domainName()
                    + ", generator config: " + config.domainName() + ", file: " + configFile.fileName());
        }
    }

    /**
     * Local file registration supports zero or one worker only; omitted
     * workerId is filled from the worker id encoded in the filename.
     */
    private static long resolveWorker(GeneratorConfigFile configFile, LocalFileGeneratorConfig config) {
        if (config.workerId() == null) {
            return configFile.workerId();
        }
        long workerId = config.workerId();
        if (workerId < 0) {
            throw new InvalidConfigException("worker id must not be negative: " + configFile.fileName());
        }
        if (workerId != configFile.workerId()) {
            throw new InvalidConfigException("worker id mismatch, filename: " + configFile.workerId()
                    + ", config: " + workerId);
        }
        return workerId;
    }

    /**
     * Inserts one filename-derived identity into an index and reports both file
     * names when a duplicate identity is found.
     */
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

    /**
     * Resolves ./uid-register/[baseDir] and rejects any baseDir that is not a
     * safe single segment under the local registration root.
     */
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

    /**
     * Parses filename ids and rejects blanks or leading signs before numeric
     * conversion, keeping file identity format strict.
     */
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

    /**
     * Generator names are filename components in local registration, so they
     * must be safe single path segments.
     */
    private static void validateGeneratorName(String generatorName, String target) {
        try {
            PathUtil.requireSafeSinglePathSegment(generatorName);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException("invalid generator name: " + target, e);
        }
    }

    /**
     * Normalizes the configured local root before child paths are resolved
     * against it.
     */
    private static Path toAbsolutePath(Path path) {
        if (path == null) {
            throw new InvalidConfigException("local registration root must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * Fails fast when required local registration directories are missing,
     * unreadable, or accidentally configured as files.
     */
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

    /**
     * Fails fast when required YAML files are missing, unreadable, or
     * accidentally configured as directories.
     */
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

    /**
     * Filename-derived generator identity plus the resolved config path.
     */
    private record GeneratorConfigFile(long generatorId, long workerId, String generatorName, Path path, String fileName) {
    }

    /**
     * In-memory lookup indexes built from every generator config filename.
     */
    private record GeneratorConfigIndex(Map<Long, GeneratorConfigFile> byGeneratorId,
                                        Map<String, GeneratorConfigFile> byGeneratorName) {
    }
}
