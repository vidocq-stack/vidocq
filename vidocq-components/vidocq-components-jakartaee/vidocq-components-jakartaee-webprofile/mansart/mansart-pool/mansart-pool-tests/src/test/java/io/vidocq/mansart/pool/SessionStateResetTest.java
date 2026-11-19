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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one borrower changes on its connection through the JDBC API does not reach the next borrower of the same
 * physical connection: the pool puts the session back as the driver opened it. The pool has a single connection
 * here, so the second borrow gets the first one's.
 */
class SessionStateResetTest {

    private StatefulDriver driver;
    private MansartDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        driver = new StatefulDriver();
        DriverManager.registerDriver(driver);
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl(StatefulDriver.PREFIX + "h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxSize(1)
                .build());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA other");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        ds.close();
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void theTransactionIsolationIsRestored() throws SQLException {
        int initial;
        try (Connection c = ds.getConnection()) {
            initial = c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        }

        assertThat(initial).isNotEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        try (Connection c = ds.getConnection()) {
            assertThat(c.getTransactionIsolation()).isEqualTo(initial);
        }
    }

    @Test
    void theSchemaIsRestored() throws SQLException {
        String initial;
        try (Connection c = ds.getConnection()) {
            initial = c.getSchema();
            c.setSchema("OTHER");
        }

        try (Connection c = ds.getConnection()) {
            assertThat(c.getSchema()).isEqualTo(initial);
        }
    }

    @Test
    void theHoldabilityIsRestored() throws SQLException {
        int initial;
        try (Connection c = ds.getConnection()) {
            initial = c.getHoldability();
            c.setHoldability(initial == ResultSet.HOLD_CURSORS_OVER_COMMIT
                    ? ResultSet.CLOSE_CURSORS_AT_COMMIT : ResultSet.HOLD_CURSORS_OVER_COMMIT);
        }

        try (Connection c = ds.getConnection()) {
            assertThat(c.getHoldability()).isEqualTo(initial);
        }
    }

    @Test
    void theReadOnlyFlagIsRestored() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setReadOnly(true);
        }

        try (Connection c = ds.getConnection()) {
            assertThat(c.isReadOnly()).isFalse();
        }
    }

    @Test
    void theCatalogIsRestored() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setCatalog("ELSEWHERE");
        }

        try (Connection c = ds.getConnection()) {
            assertThat(c.getCatalog()).isEqualTo(StatefulDriver.INITIAL_CATALOG);
        }
    }

    @Test
    void theNetworkTimeoutIsRestored() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setNetworkTimeout(Runnable::run, 5_000);
        }

        try (Connection c = ds.getConnection()) {
            assertThat(c.getNetworkTimeout()).isZero();
        }
    }

    @Test
    void theTypeMapIsRestored() throws SQLException {
        try (Connection c = ds.getConnection()) {
            Map<String, Class<?>> map = new HashMap<>();
            map.put("ADDRESS", String.class);
            c.setTypeMap(map);
        }

        try (Connection c = ds.getConnection()) {
            assertThat(c.getTypeMap()).isEmpty();
        }
    }

    @Test
    void aSessionTheBorrowerDidNotChangeIsNotTouched() throws SQLException {
        try (Connection c = ds.getConnection()) {
            assertThat(c.getSchema()).isEqualTo("PUBLIC");
        }
        driver.setterCalls.set(0);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT 1");
        }

        assertThat(driver.setterCalls.get()).as("session setters called by the release").isZero();
    }

    @Test
    void anAttributePutBackOnceIsNotPutBackAgain() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        }
        driver.setterCalls.set(0);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT 1");
        }

        assertThat(driver.setterCalls.get()).as("session setters called by the second release").isZero();
    }

    @Test
    void anAttributeTheBorrowerPutBackItselfIsNotSetAgain() throws SQLException {
        try (Connection c = ds.getConnection()) {
            int initial = c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setTransactionIsolation(initial);
            driver.setterCalls.set(0);
        }

        assertThat(driver.setterCalls.get()).as("session setters called by the release").isZero();
    }

    @Test
    void aConnectionWhoseAttributeCannotBeReadIsDiscarded() throws SQLException {
        driver.catalogReadable = false;
        try (Connection c = ds.getConnection()) {
            c.setCatalog("ELSEWHERE");
        }
        driver.catalogReadable = true;
        int connects = driver.connects.get();

        try (Connection c = ds.getConnection()) {
            assertThat(c.getCatalog()).isEqualTo(StatefulDriver.INITIAL_CATALOG);
        }
        assertThat(driver.connects.get()).as("physical connections opened").isEqualTo(connects + 1);
    }

    /**
     * H2 behind a {@code jdbc:stateful:} prefix. H2 ignores {@code setReadOnly}, {@code setCatalog},
     * {@code setNetworkTimeout} and {@code setTypeMap}, so the proxy keeps those four itself, the way a driver that
     * honours them would; everything else reaches H2. It also counts the session setters it receives.
     */
    static final class StatefulDriver implements Driver {

        static final String PREFIX = "jdbc:stateful:";
        static final String INITIAL_CATALOG = "INITIAL";

        final AtomicInteger setterCalls = new AtomicInteger();
        final AtomicInteger connects = new AtomicInteger();
        /** When false, {@code getCatalog} fails, as on a driver that cannot read it. */
        volatile boolean catalogReadable = true;
        private final Driver h2 = new org.h2.Driver();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            Connection real = h2.connect("jdbc:" + url.substring(PREFIX.length()), info);
            connects.incrementAndGet();
            AtomicReference<Boolean> readOnly = new AtomicReference<>(false);
            AtomicReference<String> catalog = new AtomicReference<>(INITIAL_CATALOG);
            AtomicReference<Integer> networkTimeout = new AtomicReference<>(0);
            AtomicReference<Map<String, Class<?>>> typeMap = new AtomicReference<>(Map.of());
            return (Connection) Proxy.newProxyInstance(SessionStateResetTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (_, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("set") && !name.equals("setAutoCommit")
                                && !name.equals("setSavepoint")) {
                            setterCalls.incrementAndGet();
                        }
                        switch (name) {
                            case "setReadOnly"       -> { readOnly.set((Boolean) args[0]); return null; }
                            case "isReadOnly"        -> { return readOnly.get(); }
                            case "setCatalog"        -> { catalog.set((String) args[0]); return null; }
                            case "getCatalog"        -> {
                                if (!catalogReadable) throw new SQLException("catalog not readable");
                                return catalog.get();
                            }
                            case "setNetworkTimeout" -> { networkTimeout.set((Integer) args[1]); return null; }
                            case "getNetworkTimeout" -> { return networkTimeout.get(); }
                            case "setTypeMap"        -> { typeMap.set(Map.copyOf(cast(args[0]))); return null; }
                            case "getTypeMap"        -> { return new HashMap<>(typeMap.get()); }
                            default                  -> { /* H2 answers */ }
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Class<?>> cast(Object map) {
            return (Map<String, Class<?>>) map;
        }

        @Override public boolean acceptsURL(String url)                                 { return url.startsWith(PREFIX); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion()                                          { return 1; }
        @Override public int getMinorVersion()                                          { return 0; }
        @Override public boolean jdbcCompliant()                                        { return false; }
        @Override public Logger getParentLogger()                                       { return Logger.getGlobal(); }
    }
}
