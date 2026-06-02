package com.zed.idgen.snowflake.core.manager.mysql;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import com.zed.idgen.snowflake.core.util.YamlConfigLoader;

import java.nio.file.Path;

/**
 * User-provided MySQL connection config.
 * <p>
 * Example YAML:
 * jdbcUrl: jdbc:mysql://127.0.0.1:3306/z_id_generator
 * username: root
 * password: secret
 * driverClassName: com.mysql.cj.jdbc.Driver
 * defaultStatementTimeoutSeconds: 5
 * leaseRequestTimeoutSeconds: 2
 */
public record MysqlConfig(
        String jdbcUrl,
        String username,
        String password,
        String driverClassName,
        Integer defaultStatementTimeoutSeconds,
        Integer leaseRequestTimeoutSeconds) {

    private static final String DEFAULT_DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";
    private static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_LEASE_REQUEST_TIMEOUT_SECONDS = 2;

    public MysqlConfig {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new InvalidConfigException("mysql jdbcUrl must not be blank");
        }
        if (username == null || username.isBlank()) {
            throw new InvalidConfigException("mysql username must not be blank");
        }
        if (password == null) {
            password = "";
        }
        if (driverClassName == null || driverClassName.isBlank()) {
            driverClassName = DEFAULT_DRIVER_CLASS_NAME;
        }
        if (defaultStatementTimeoutSeconds == null) {
            defaultStatementTimeoutSeconds = DEFAULT_STATEMENT_TIMEOUT_SECONDS;
        }
        if (defaultStatementTimeoutSeconds <= 0) {
            throw new InvalidConfigException("mysql default statement timeout seconds must be positive");
        }
        if (leaseRequestTimeoutSeconds == null) {
            leaseRequestTimeoutSeconds = DEFAULT_LEASE_REQUEST_TIMEOUT_SECONDS;
        }
        if (leaseRequestTimeoutSeconds <= 0) {
            throw new InvalidConfigException("mysql lease request timeout seconds must be positive");
        }
    }

    public static MysqlConfig load(Path configPath) {
        if (configPath == null) {
            throw new InvalidConfigException("mysql config path must not be null");
        }
        return YamlConfigLoader.load(configPath, MysqlConfig.class);
    }
}
