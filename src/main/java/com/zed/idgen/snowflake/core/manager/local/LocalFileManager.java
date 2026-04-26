package com.zed.idgen.snowflake.core.manager.local;

import com.zed.idgen.snowflake.core.constant.*;
import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.manager.RegistrationLoader;
import com.zed.idgen.snowflake.core.util.PathUtil;
import com.zed.idgen.snowflake.core.util.YamlConfigLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class LocalFileManager implements RegistrationLoader {
    private final Path domainBasePath;
    private final Path generatorBasePath;

    public LocalFileManager(String domainBasePath, String generatorBasePath) {

        this.domainBasePath = PathUtil.getReadableDir(domainBasePath);
        this.generatorBasePath = PathUtil.getReadableDir(generatorBasePath);
    }

    private IdDomainInfo loadDomainByName(String name) {
        Path path = resolveConfigPath(domainBasePath, name, "domain");
        IdDomainInfo info = YamlConfigLoader.load(path, IdDomainInfo.class);
        if (info == null) {
            throw new InvalidConfigException("domain config is empty: " + name);
        }
        if (info.domainName() != null && !info.domainName().isBlank()) {
            if (!info.domainName().equals(name)) {
                throw new InvalidConfigException("domain name mismatch, passed name: " + name + "config: " + info);
            }
        } else {
            // set domain name
            info = new IdDomainInfo(info.domainId(), name, info.prefixType(), info.timeBit(), info.workerBit(), info.sequenceBit(), info.epochStartMs(), info.timeUnit());
        }
        return info;
    }

    @Override
    public GeneratorInstanceProperties loadById(long generatorId) {
        throw new UnsupportedOperationException("local file loading by generator id is not supported yet");
    }

    @Override
    public GeneratorInstanceProperties loadByName(String generatorName) {
        Path path = resolveConfigPath(generatorBasePath, generatorName, "generator");
        GeneratorConfig config = YamlConfigLoader.load(path, GeneratorConfig.class);
        validateGeneratorConfig(generatorName, config);
        IdDomainInfo domainInfo = loadDomainByName(config.domainName());
        if (!Objects.equals(domainInfo.domainId(), config.domainId())) {
            throw new InvalidConfigException("domain name mismatch, passed name: " + config.domainName() + " config: " + domainInfo);
        }
        if (!domainInfo.domainName().equals(config.domainName())) {
            throw new InvalidConfigException("domain name mismatch, domain config: " + domainInfo + "generator config: " + config);
        }

        return new GeneratorInstanceProperties(
                domainInfo,
                new GeneratorInfo(
                        config.generatorId(),
                        config.generatorName(),
                        config.domainId(),
                        config.domainName(),
                        config.generationPolicy()
                ),
                List.copyOf(config.workers())
        );
    }

    private static Path resolveConfigPath(Path basePath, String fileName, String configType) {
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidConfigException(configType + " config name must not be blank");
        }

        var resolvedPath = PathUtil.getFilePath(basePath, fileName);
        if (!resolvedPath.startsWith(basePath)) {
            throw new InvalidConfigException(configType + " config path escapes base directory: " + fileName);
        }
        return resolvedPath;
    }



    private static void validateGeneratorConfig(String requestedName, GeneratorConfig config) {
        if (config == null) {
            throw new InvalidConfigException("generator config is empty: " + requestedName);
        }
        if (config.generatorName() == null || config.generatorName().isBlank()) {
            throw new InvalidConfigException("generator name must not be blank: " + requestedName);
        }
        if (!requestedName.equals(config.generatorName())) {
            throw new InvalidConfigException("generator name mismatch, passed name: " + requestedName + " config: " + config);
        }
        if (config.domainName() == null || config.domainName().isBlank()) {
            throw new InvalidConfigException("generator config must explicitly set domain name: " + requestedName);
        }
        if (config.workers() == null || config.workers().size() != 1 || config.workers().getFirst() == null) {
            throw new InvalidConfigException("local generator config must contain exactly one worker: " + requestedName);
        }
        if (config.generationPolicy() == null) {
            throw new InvalidConfigException("generation policy must not be null: " + requestedName);
        }
    }

}
