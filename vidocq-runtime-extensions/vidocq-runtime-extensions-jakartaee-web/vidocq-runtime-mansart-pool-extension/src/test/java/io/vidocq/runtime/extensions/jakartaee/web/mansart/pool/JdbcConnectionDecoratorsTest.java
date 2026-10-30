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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The hook a telemetry glue module uses to trace JDBC (BUG-20261030-25): every connection the application gets from a
 * Vidocq DataSource goes through the installed {@link JdbcConnectionDecorator}s, with the datasource's name; with none
 * installed, the pool's own connection comes back untouched.
 */
class JdbcConnectionDecoratorsTest {

    private static final Connection RAW = new H2Less().connection();

    @AfterEach
    void reset() {
        JdbcConnectionDecorators.install(List.of());
        NamedDataSourceRegistry.unregister("reports");
    }

    @Test
    void withoutDecoratorTheConnectionIsThePoolsOwn() throws Exception {
        NamedDataSourceRegistry.register("reports", new H2Less());

        assertSame(RAW, new AbstractNamedDataSourceHolder("reports") { }.getConnection());
    }

    @Test
    void aNamedHolderHandsItsConnectionToTheDecoratorsWithItsName() throws Exception {
        Connection traced = new H2Less().connection();
        StringBuilder seen = new StringBuilder();
        JdbcConnectionDecorators.install(List.of((name, connection) -> {
            seen.append(name);
            return connection == RAW ? traced : connection;
        }));
        NamedDataSourceRegistry.register("reports", new H2Less());

        assertSame(traced, new AbstractNamedDataSourceHolder("reports") { }.getConnection());
        assertEquals("reports", seen.toString());
    }

    @Test
    void decoratorsApplyInOrder() throws Exception {
        JdbcConnectionDecorators.install(List.of((name, c) -> c, (name, c) -> RAW));

        assertSame(RAW, JdbcConnectionDecorators.decorate("default", new H2Less().connection()));
    }

    /** A DataSource that hands out one fixed, inert Connection: enough to see who wrapped it. */
    private static final class H2Less implements DataSource {
        private static final Connection CONNECTION = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                H2Less.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> null);

        Connection connection() { return CONNECTION; }
        @Override public Connection getConnection() { return CONNECTION; }
        @Override public Connection getConnection(String u, String p) { return CONNECTION; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {
            // the stub keeps no log writer
        }
        @Override public void setLoginTimeout(int seconds) {
            // the stub never times out
        }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
