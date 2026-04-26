package com.zed.idgen.snowflake.core.util;

import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;

public class YamlConfigLoader {
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public static <T> T load(Path path, Class<T> clazz) {
        return YAML_MAPPER.readValue(path.toFile(), clazz);
    }
}