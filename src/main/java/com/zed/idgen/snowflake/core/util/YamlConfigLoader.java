package com.zed.idgen.snowflake.core.util;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;

public final class YamlConfigLoader {
    /*
     * Config files are registration contracts, so unknown fields should fail
     * fast instead of being silently ignored as a misspelled or obsolete option.
     */
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

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
