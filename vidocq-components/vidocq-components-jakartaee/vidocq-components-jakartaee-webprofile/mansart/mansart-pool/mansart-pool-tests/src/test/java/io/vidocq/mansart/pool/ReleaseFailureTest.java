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
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A driver that fails with a {@link RuntimeException} while the pool resets a connection costs the pool that
 * connection, not its place: the next borrower gets a new one (MANSART-057).
 */
class ReleaseFailureTest {

    private FailingDriver driver;
    private MansartDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        driver = new FailingDriver();
        DriverManager.registerDriver(driver);
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl(FailingDriver.PREFIX + "h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxSize(1)
                .acquireTimeout(Duration.ofMillis(200))
                .build());
    }

    @AfterEach
    void tearDown() throws SQLException {
        ds.close();
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void aRuntimeExceptionDuringTheResetGivesThePlaceBack() throws SQLException {
        driver.failOnClearWarnings.set(true);
        ds.getConnection().close();
        driver.failOnClearWarnings.set(false);

        try (Connection next = ds.getConnection()) {
            assertThat(next.isValid(1)).isTrue();
        }
        assertThat(ds.snapshot().active()).isZero();
    }

    /** An H2 driver whose connections throw an unchecked exception from {@code clearWarnings()} on demand. */
    private static final class FailingDriver implements Driver {

        static final String PREFIX = "jdbc:failing:";
        final AtomicBoolean failOnClearWarnings = new AtomicBoolean();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            Connection real = DriverManager.getConnection("jdbc:" + url.substring(PREFIX.length()), info);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("clearWarnings") && failOnClearWarnings.get()) {
                            throw new IllegalStateException("driver bug");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override public boolean acceptsURL(String url)                                     { return url.startsWith(PREFIX); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)  { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion()                                              { return 1; }
        @Override public int getMinorVersion()                                              { return 0; }
        @Override public boolean jdbcCompliant()                                            { return false; }
        @Override public Logger getParentLogger()                                           { return Logger.getGlobal(); }
    }
}
