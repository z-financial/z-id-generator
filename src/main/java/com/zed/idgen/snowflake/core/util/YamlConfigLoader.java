package com.zed.idgen.snowflake.core.util;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;

public final class YamlConfigLoader {
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    private YamlConfigLoader() {
    }

    public static <T> T load(Path path, Class<T> clazz) {
        try {
            return YAML_MAPPER.readValue(path.toFile(), clazz);
        } catch (Exception e) {
            throw new InvalidConfigException("failed to load YAML config: " + path, e);
        }
    }
}
