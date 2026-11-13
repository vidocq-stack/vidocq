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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.UnitDataSources.JTA_DATA_SOURCE;
import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.UnitDataSources.NON_JTA_DATA_SOURCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitTransactions;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class UnitDataSourcesTest {

    private static final DataSource DEFAULT_POOL = new Source("default");
    private static final DataSource ORDERS_POOL = new Source("orders");
    private static final UnaryOperator<DataSource> ENLIST = Enlisted::new;

    private static final UnitDataSources.Lookup BOTH_POOLS = lookup(DEFAULT_POOL);
    private static final UnitDataSources.Lookup NAMED_POOL_ONLY = lookup(null);

    @Test
    void aJtaUnitTakesTheSourceItNamesEnlistedAndAllocatesOnItOutsideTheTransaction() {
        Map<String, Object> properties = UnitDataSources.properties("shop", jta("orders", null), BOTH_POOLS, ENLIST);

        assertEquals(new Enlisted(ORDERS_POOL), properties.get(JTA_DATA_SOURCE));
        assertSame(ORDERS_POOL, properties.get(NON_JTA_DATA_SOURCE));
    }

    @Test
    void aJtaUnitThatNamesNoSourceTakesTheDefaultPool() {
        Map<String, Object> properties = UnitDataSources.properties("shop", jta(null, null), BOTH_POOLS, ENLIST);

        assertEquals(new Enlisted(DEFAULT_POOL), properties.get(JTA_DATA_SOURCE));
        assertSame(DEFAULT_POOL, properties.get(NON_JTA_DATA_SOURCE));
    }

    @Test
    void aJtaUnitKeepsTheNonJtaSourceItNames() {
        Map<String, Object> properties = UnitDataSources.properties("shop", jta(null, "orders"), BOTH_POOLS, ENLIST);

        assertEquals(new Enlisted(DEFAULT_POOL), properties.get(JTA_DATA_SOURCE));
        assertSame(ORDERS_POOL, properties.get(NON_JTA_DATA_SOURCE));
    }

    @Test
    void aJtaUnitWhoseNamedSourceIsMissingIsRefusedWithTheKeyToSet() {
        UnitTransactions billing = jta("billing", null);
        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> UnitDataSources.properties("shop", billing, BOTH_POOLS, ENLIST));

        assertTrue(refused.getMessage().contains("Persistence unit shop"), refused.getMessage());
        assertTrue(refused.getMessage().contains("'billing'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("vidocq.pool.billing.url"), refused.getMessage());
    }

    @Test
    void aJtaUnitWithoutADefaultPoolIsRefusedWithTheKeyToSet() {
        UnitTransactions unnamed = jta(null, null);
        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> UnitDataSources.properties("shop", unnamed, NAMED_POOL_ONLY, ENLIST));

        assertTrue(refused.getMessage().contains("Persistence unit shop"), refused.getMessage());
        assertTrue(refused.getMessage().contains("vidocq.pool.url"), refused.getMessage());
    }

    @Test
    void aResourceLocalUnitTakesTheSourceItNamesAsIs() {
        Map<String, Object> properties = UnitDataSources.properties("shop", resourceLocal("orders"), BOTH_POOLS,
                ENLIST);

        assertSame(ORDERS_POOL, properties.get(NON_JTA_DATA_SOURCE));
        assertFalse(properties.containsKey(JTA_DATA_SOURCE));
    }

    @Test
    void aResourceLocalUnitThatNamesNoSourceTakesTheDefaultPool() {
        Map<String, Object> properties = UnitDataSources.properties("shop", resourceLocal(null), BOTH_POOLS, ENLIST);

        assertSame(DEFAULT_POOL, properties.get(NON_JTA_DATA_SOURCE));
        assertFalse(properties.containsKey(JTA_DATA_SOURCE));
    }

    @Test
    void aResourceLocalUnitWithoutAnyPoolKeepsTheConnectionSettingsOfItsDescriptor() {
        assertEquals(Map.of(), UnitDataSources.properties("shop", resourceLocal(null), NAMED_POOL_ONLY, ENLIST));
    }

    @Test
    void aResourceLocalUnitWhoseNamedSourceIsMissingIsRefused() {
        UnitTransactions billing = resourceLocal("billing");
        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> UnitDataSources.properties("shop", billing, BOTH_POOLS, ENLIST));

        assertTrue(refused.getMessage().contains("vidocq.pool.billing.url"), refused.getMessage());
    }

    private static UnitTransactions jta(String jtaDataSource, String nonJtaDataSource) {
        return new UnitTransactions(PersistenceUnitTransactionType.JTA, jtaDataSource, nonJtaDataSource);
    }

    private static UnitTransactions resourceLocal(String nonJtaDataSource) {
        return new UnitTransactions(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, nonJtaDataSource);
    }

    private static UnitDataSources.Lookup lookup(DataSource defaultPool) {
        return new UnitDataSources.Lookup() {
            @Override
            public Optional<DataSource> named(String name) {
                return "orders".equals(name) ? Optional.of(ORDERS_POOL) : Optional.empty();
            }

            @Override
            public Optional<DataSource> byDefault() {
                return Optional.ofNullable(defaultPool);
            }
        };
    }

    /** What the enlisting wrapper would hand Noether: compared by the pool it wraps. */
    private record Enlisted(DataSource pool) implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String user, String password) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* a stand-in: nothing is logged */ }
        @Override public void setLoginTimeout(int seconds) { /* a stand-in: no timeout */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }

    /** A pool told apart by its name. */
    private record Source(String name) implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String user, String password) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* a stand-in: nothing is logged */ }
        @Override public void setLoginTimeout(int seconds) { /* a stand-in: no timeout */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
