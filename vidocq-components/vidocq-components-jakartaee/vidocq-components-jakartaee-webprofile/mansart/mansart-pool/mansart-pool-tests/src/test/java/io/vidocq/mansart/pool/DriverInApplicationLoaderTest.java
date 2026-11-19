/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pool in one class loader, the JDBC driver in a child one: the shape of Vidocq's runtime layer and
 * application layer (mansart#42). {@code DriverManager} refuses a driver its caller cannot load, so the pool
 * must find the driver through the context class loader.
 */
class DriverInApplicationLoaderTest {

    @Test
    void aDriverOnlyTheApplicationLoaderSeesIsUsed() throws Exception {
        try (var poolLoader = poolLoader(); var appLoader = new URLClassLoader("app",
                new URL[]{location(org.h2.Driver.class)}, poolLoader)) {
            Object ds = withContextLoader(appLoader, () -> dataSource(poolLoader,
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", 0));
            try {
                assertThat(selectFortyTwo(appLoader, ds)).isEqualTo(42);
            } finally {
                ds.getClass().getMethod("close").invoke(ds);
            }
        }
    }

    @Test
    void minIdleOpensWithTheLoaderCapturedAtConstruction() throws Exception {
        try (var poolLoader = poolLoader(); var appLoader = new URLClassLoader("app",
                new URL[]{location(org.h2.Driver.class)}, poolLoader)) {
            Object ds = withContextLoader(appLoader, () -> dataSource(poolLoader,
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", 2));
            try {
                Object metrics = ds.getClass().getMethod("snapshot").invoke(ds);
                assertThat(metrics.getClass().getMethod("idle").invoke(metrics)).isEqualTo(2);
                // a third connection while the two idle ones are held: the pool must open() it on a thread
                // whose context loader cannot see H2
                assertThat(withContextLoader(ClassLoader.getPlatformClassLoader(), () -> {
                    Method getConnection = ds.getClass().getMethod("getConnection");
                    try (var _ = (Connection) getConnection.invoke(ds);
                         var _ = (Connection) getConnection.invoke(ds);
                         Connection third = (Connection) getConnection.invoke(ds);
                         Statement s = third.createStatement(); ResultSet rs = s.executeQuery("SELECT 42")) {
                        rs.next();
                        return rs.getInt(1);
                    }
                })).isEqualTo(42);
            } finally {
                ds.getClass().getMethod("close").invoke(ds);
            }
        }
    }

    @Test
    void noDriverForTheUrlFailsWithAClearMessageAndNoPassword() throws Exception {
        try (var poolLoader = poolLoader()) {
            Object ds = withContextLoader(poolLoader, () -> dataSource(poolLoader, "jdbc:nosuchdb:x", 0));
            try {
                assertThatThrownBy(() -> {
                    try {
                        ds.getClass().getMethod("getConnection").invoke(ds);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }).hasMessageContaining("No JDBC driver accepts jdbc:nosuchdb:x")
                  .hasMessageNotContaining("s3cret");
            } finally {
                ds.getClass().getMethod("close").invoke(ds);
            }
        }
    }

    private static URLClassLoader poolLoader() {
        return new URLClassLoader("pool", new URL[]{location(PoolConfig.class), location(MansartDataSource.class)},
                ClassLoader.getPlatformClassLoader());
    }

    /** A MansartDataSource built through {@code poolLoader}'s own copies of the pool classes. */
    private static Object dataSource(ClassLoader poolLoader, String url, int minIdle) throws Exception {
        Class<?> configType = poolLoader.loadClass("io.vidocq.mansart.pool.PoolConfig");
        Object builder = configType.getMethod("builder").invoke(null);
        Class<?> builderType = configType.getMethod("builder").getReturnType();
        builderType.getMethod("jdbcUrl", String.class).invoke(builder, url);
        builderType.getMethod("username", String.class).invoke(builder, "sa");
        builderType.getMethod("password", String.class).invoke(builder, "s3cret");
        builderType.getMethod("minIdle", int.class).invoke(builder, minIdle);
        builderType.getMethod("maxSize", int.class).invoke(builder, 4);
        Object config = builderType.getMethod("build").invoke(builder);
        Class<?> dataSourceType = poolLoader.loadClass("io.vidocq.mansart.pool.core.MansartDataSource");
        return dataSourceType.getMethod("of", configType).invoke(null, config);
    }

    private static int selectFortyTwo(ClassLoader contextLoader, Object ds) throws Exception {
        return withContextLoader(contextLoader, () -> {
            try (Connection c = (Connection) ds.getClass().getMethod("getConnection").invoke(ds);
                 Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 42")) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    private interface Action<T> {
        T run() throws Exception;
    }

    private static <T> T withContextLoader(ClassLoader loader, Action<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL location(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
