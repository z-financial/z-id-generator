package com.zed.idgen.snowflake.core.manager.mysql.dao;

import com.zed.idgen.snowflake.core.exception.InvalidConfigException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlDaoFactoryTest {

    @Test
    void closingFactoryDoesNotCloseBorrowedDataSource() {
        AtomicBoolean dataSourceClosed = new AtomicBoolean(false);
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DataSource.class, AutoCloseable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        dataSourceClosed.set(true);
                    }
                    return defaultValue(method.getReturnType());
                });

        try (MysqlDaoFactory factory = new MysqlDaoFactory(dataSource, 5, 2)) {
            // Construction must not require a connection from the borrowed pool.
        }

        assertFalse(dataSourceClosed.get());
    }

    @Test
    void borrowedDataSourceConfigurationMustBeValid() {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));

        assertThrows(InvalidConfigException.class, () -> new MysqlDaoFactory(null, 5, 2));
        assertThrows(InvalidConfigException.class, () -> new MysqlDaoFactory(dataSource, 0, 2));
        assertThrows(InvalidConfigException.class, () -> new MysqlDaoFactory(dataSource, 5, 0));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
