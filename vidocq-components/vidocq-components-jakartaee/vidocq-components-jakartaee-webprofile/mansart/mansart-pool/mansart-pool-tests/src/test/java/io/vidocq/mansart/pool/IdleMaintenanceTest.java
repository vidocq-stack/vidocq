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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the pool does with its idle connections between two borrows: in {@link ValidationMode#PERIODIC} mode it
 * checks them and closes the dead ones (MANSART-058), it checks with the {@code validationQuery} when one is
 * configured, and it holds {@code minIdle} connections through the idle timeout (MANSART-059).
 */
class IdleMaintenanceTest {

    private RecordingDriver driver;
    private MansartDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        driver = new RecordingDriver();
        DriverManager.registerDriver(driver);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (ds != null) ds.close();
        DriverManager.deregisterDriver(driver);
    }

    private PoolConfig.Builder config() {
        return PoolConfig.builder()
                .jdbcUrl(RecordingDriver.PREFIX + "h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("");
    }

    @Test
    void periodicValidationClosesAnIdleConnectionTheDatabaseDropped() throws Exception {
        ds = MansartDataSource.of(config().idleTimeout(Duration.ofSeconds(4)).build());
        assertThat(ds.config().validation()).isEqualTo(ValidationMode.PERIODIC);
        ds.getConnection().close();
        assertThat(ds.snapshot().idle()).isEqualTo(1);

        driver.dead.set(true);

        await(() -> ds.snapshot().idle() == 0);
    }

    @Test
    void theValidationQueryIsWhatChecksAConnectionOnBorrow() throws Exception {
        ds = MansartDataSource.of(config().validationQuery("SELECT 42").build());
        assertThat(ds.config().validation()).isEqualTo(ValidationMode.ON_BORROW);
        ds.getConnection().close();

        ds.getConnection().close();

        assertThat(driver.statements).contains("SELECT 42");
    }

    @Test
    void minIdleConnectionsOutliveTheIdleTimeout() throws Exception {
        ds = MansartDataSource.of(config().minIdle(2).idleTimeout(Duration.ofSeconds(1)).build());
        assertThat(ds.snapshot().idle()).isEqualTo(2);

        // The housekeeper checks both connections on each pass, one pass a second: three passes are three seconds,
        // three idle timeouts.
        await(() -> driver.checks.get() >= 6);

        assertThat(ds.snapshot().idle()).isEqualTo(2);
        assertThat(driver.opened).as("connections opened: the two of minIdle, kept").hasValue(2);
    }

    @Test
    void thePoolOpensConnectionsAgainToReachMinIdle() throws Exception {
        ds = MansartDataSource.of(config().minIdle(2).idleTimeout(Duration.ofSeconds(4)).build());
        driver.dead.set(true);
        await(() -> ds.snapshot().idle() == 0);

        driver.dead.set(false);

        await(() -> ds.snapshot().idle() == 2);
    }

    @Test
    void aConnectionThatFailsItsValidationQueryIsReplacedOnBorrow() throws Exception {
        ds = MansartDataSource.of(config().validationQuery("SELECT 42").build());
        ds.getConnection().close();
        driver.broken.set(true);

        try (Connection fresh = ds.getConnection()) {
            assertThat(fresh.isClosed()).isFalse();
        }
        assertThat(driver.opened).as("connections opened: the broken one, then its replacement").hasValue(2);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached within 6 s").isLessThan(deadline);
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
    }

    /** An H2 driver that counts its connections, records the SQL of plain statements and can play dead. */
    private static final class RecordingDriver implements Driver {

        static final String PREFIX = "jdbc:recording:";
        final AtomicBoolean dead = new AtomicBoolean();
        /** The connections opened so far fail every statement; new ones work. */
        final AtomicBoolean broken = new AtomicBoolean();
        final AtomicInteger checks = new AtomicInteger();
        final AtomicInteger opened = new AtomicInteger();
        final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            if (dead.get()) throw new SQLException("database down", "08001");
            Connection real = DriverManager.getConnection("jdbc:" + url.substring(PREFIX.length()), info);
            int rank = opened.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("isValid")) {
                            checks.incrementAndGet();
                            if (dead.get()) return false;
                        }
                        if (method.getName().equals("createStatement") && broken.get() && rank == 1) {
                            throw new SQLException("connection reset", "08006");
                        }
                        Object result = invoke(real, method, args);
                        return method.getName().equals("createStatement") ? recording((Statement) result) : result;
                    });
        }

        private Statement recording(Statement real) {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("execute") && args != null) statements.add((String) args[0]);
                        return invoke(real, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override public boolean acceptsURL(String url)                                     { return url.startsWith(PREFIX); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)  { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion()                                              { return 1; }
        @Override public int getMinorVersion()                                              { return 0; }
        @Override public boolean jdbcCompliant()                                            { return false; }
        @Override public Logger getParentLogger()                                           { return Logger.getGlobal(); }
    }
}
