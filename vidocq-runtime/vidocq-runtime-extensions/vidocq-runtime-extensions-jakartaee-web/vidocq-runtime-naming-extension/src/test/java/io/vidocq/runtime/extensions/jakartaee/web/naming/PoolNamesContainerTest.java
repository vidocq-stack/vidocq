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

import io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
import io.vidocq.mansart.transactions.jdbc.JtaDataSource;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.vauban.naming.JndiName;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names of the pools in a running container (Vidocq/vidocq#676): the {@code DataSource} beans as CDI resolves
 * them, through Mansart's {@code JtaDataSource} when the container has transactions, as they are otherwise.
 */
class PoolNamesContainerTest {

    /** Keeps a {@code @Named} pool out of the {@code @Default} beans, as the marker of the generated holders does. */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NotDefault {
    }

    /** A pool that opens no connection: the tests look at which data source a name answers. */
    public abstract static class Pool implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* nothing to log */ }
        @Override public void setLoginTimeout(int seconds) { /* no login */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> type) { return type.cast(this); }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    }

    /** The default pool. */
    @Singleton
    public static class DefaultPool extends Pool implements DataSource {
    }

    /** The pool named {@code orders}. */
    @Named("orders")
    @NotDefault
    @Singleton
    public static class OrdersPool extends Pool implements DataSource {
    }

    private static VaubanContainerBuilder pools() {
        return new VaubanContainerBuilder().addComponentProvider(new _VaubanComponents())
                .addComponentProvider(new _VaubanComponentsTests())
                .addBeanClass(PoolNames.class).addBeanClass(DefaultPool.class).addBeanClass(OrdersPool.class);
    }

    private static DataSource lookedUp(VaubanContainer container, String name) throws Exception {
        Optional<Object> found = container.select(PoolNames.class).lookup(JndiName.parse(name));
        return assertInstanceOf(DataSource.class, found.orElseThrow(() -> new AssertionError(name + " is not answered")));
    }

    @Test
    void withoutTransactionsANameAnswersThePoolItself() throws Exception {
        try (var container = pools().build()) {
            DataSource orders = container.getBeanManager().createInstance()
                    .select(DataSource.class, NamedLiteral.of("orders")).get();

            assertSame(orders, lookedUp(container, "java:comp/env/orders"));
            assertSame(container.select(DataSource.class), lookedUp(container, "java:comp/DefaultDataSource"));
        }
    }

    @Test
    void withTransactionsANameAnswersThePoolThroughTheTransactionsOfTheApplication() throws Exception {
        try (var container = pools().addBeanClass(MansartTransactionsExtension.class).build()) {
            DataSource orders = lookedUp(container, "java:comp/env/orders");
            DataSource byDefault = lookedUp(container, "java:comp/DefaultDataSource");

            assertInstanceOf(JtaDataSource.class, orders);
            assertInstanceOf(OrdersPool.class, orders.unwrap(Pool.class));
            assertInstanceOf(JtaDataSource.class, byDefault);
            assertInstanceOf(DefaultPool.class, byDefault.unwrap(Pool.class));
        }
    }

    @Test
    void aNameNoPoolHasIsNotAnswered() throws Exception {
        try (var container = pools().build()) {
            PoolNames names = container.select(PoolNames.class);

            assertEquals(Optional.empty(), names.lookup(JndiName.parse("java:comp/env/billing")));
        }
    }

    @Test
    void theEnvironmentIsAContextOfTheNamedPools() throws Exception {
        try (var container = pools().build()) {
            PoolNames names = container.select(PoolNames.class);

            assertTrue(names.isContext(JndiName.parse("java:comp/env")));
            assertFalse(names.isContext(JndiName.parse("java:comp/env/orders")));
        }
    }
}
