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
package io.vidocq.runtime.extensions.jakartaee.web.naming;

import io.vidocq.vauban.naming.JndiName;
import org.junit.jupiter.api.Test;

import javax.naming.InvalidNameException;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The names the pools of the application answer by default, as the data sources of an application server do. */
class PoolNamesTest {

    /** A data source that is only itself. */
    private record Source(String label) implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(label); }
        @Override public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(label); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* nothing to log */ }
        @Override public void setLoginTimeout(int seconds) { /* no login */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(label); }
        @Override public <T> T unwrap(Class<T> type) { return type.cast(this); }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    }

    /** A pool seen through the transactions of the application. */
    private static DataSource enlisting(DataSource pool) {
        return new Source("enlisting " + ((Source) pool).label());
    }

    private static final DataSource DEFAULT = new Source("default");
    private static final DataSource ORDERS = new Source("orders");
    private static final DataSource BILLING = new Source("jdbc/billing");

    /** The pools of an application: the default one when {@code byDefault} is not null, and the named ones. */
    private record Pools(DataSource byDefaultOrNull, Map<String, DataSource> named) implements PoolNames.Pools {
        @Override public Optional<DataSource> named(String name) { return Optional.ofNullable(named.get(name)); }
        @Override public Optional<DataSource> byDefault() { return Optional.ofNullable(byDefaultOrNull); }
        @Override public Set<String> names() { return named.keySet(); }
    }

    private static final Pools ALL = new Pools(DEFAULT, Map.of("orders", ORDERS, "jdbc/billing", BILLING));

    private static JndiName name(String name) {
        try {
            return JndiName.parse(name);
        } catch (InvalidNameException e) {
            throw new AssertionError(e);
        }
    }

    private static Optional<Object> lookup(PoolNames.Pools pools, String name) {
        return lookup(new PoolNames(pools, UnaryOperator.identity()), name);
    }

    private static Optional<Object> lookup(PoolNames names, String name) {
        try {
            return names.lookup(name(name));
        } catch (NamingException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theDefaultDataSourceOfThePlatformIsTheDefaultPool() {
        assertSame(DEFAULT, lookup(ALL, "java:comp/DefaultDataSource").orElseThrow());
    }

    @Test
    void aNamedPoolAnswersItsNameInTheEnvironmentOfTheComponent() {
        assertSame(ORDERS, lookup(ALL, "java:comp/env/orders").orElseThrow());
        assertSame(BILLING, lookup(ALL, "java:comp/env/jdbc/billing").orElseThrow());
    }

    @Test
    void aNameNoPoolHasIsNotAnswered() {
        assertEquals(Optional.empty(), lookup(ALL, "java:comp/env/absent"));
        assertEquals(Optional.empty(), lookup(ALL, "java:comp/env"));
        assertEquals(Optional.empty(), lookup(ALL, "java:app/env/orders"));
        assertEquals(Optional.empty(), lookup(ALL, "java:comp/orders"));
    }

    @Test
    void withoutADefaultPoolTheDefaultDataSourceIsNotAnswered() {
        assertEquals(Optional.empty(), lookup(new Pools(null, Map.of()), "java:comp/DefaultDataSource"));
    }

    @Test
    void aPoolIsAnsweredThroughTheTransactionsOfTheApplication() {
        PoolNames names = new PoolNames(ALL, PoolNamesTest::enlisting);

        assertEquals(new Source("enlisting orders"), lookup(names, "java:comp/env/orders").orElseThrow());
        assertEquals(new Source("enlisting default"), lookup(names, "java:comp/DefaultDataSource").orElseThrow());
    }

    @Test
    void severalBeansForOneNameAreANamingFailureThatNamesIt() {
        PoolNames names = new PoolNames(new PoolNames.Pools() {
            @Override public Optional<DataSource> named(String name) { throw new IllegalStateException("several beans are " + name); }
            @Override public Optional<DataSource> byDefault() { return Optional.empty(); }
            @Override public Set<String> names() { return Set.of(); }
        }, UnaryOperator.identity());

        NamingException failure = assertThrows(NamingException.class, () -> names.lookup(name("java:comp/env/orders")));

        assertEquals("java:comp/env/orders: several beans are orders", failure.getMessage());
    }

    @Test
    void theEnvironmentAndThePrefixesOfThePoolNamesAreContexts() {
        PoolNames names = new PoolNames(ALL, UnaryOperator.identity());

        assertTrue(names.isContext(name("java:comp/env")));
        assertTrue(names.isContext(name("java:comp/env/jdbc")));
        assertFalse(names.isContext(name("java:comp/env/orders")), "a pool, not a context");
        assertFalse(names.isContext(name("java:comp/env/jd")), "not a whole segment");
        assertFalse(names.isContext(name("java:app/env")));
    }

    @Test
    void withoutANamedPoolTheEnvironmentIsNotAContextOfThePools() {
        assertFalse(new PoolNames(new Pools(DEFAULT, Map.of()), UnaryOperator.identity()).isContext(name("java:comp/env")));
    }
}
