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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitTransactions;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * A JTA unit shares the connection of the transaction through Mansart Transactions: without its beans, the unit is
 * refused, with the extension to add. The unit {@code orders} of the test {@code META-INF/persistence.xml} is JTA, on
 * the default pool.
 */
class VidocqPersistenceUnitBootstrapTest {

    @Test
    void a_jta_unit_without_a_transaction_manager_names_the_extension_to_add() {
        var bootstrap = new VidocqPersistenceUnitBootstrap(new Beans<>(new Pool()), new Beans<>(null),
                new Beans<>(null));

        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> bootstrap.create("orders", null));

        assertTrue(refused.getMessage().contains("no TransactionManager bean"), refused.getMessage());
        assertTrue(refused.getMessage().contains("vidocq extension add mansart-transactions"), refused.getMessage());
    }

    @Test
    void a_jta_unit_without_a_synchronization_registry_names_the_extension_to_add() {
        var bootstrap = new VidocqPersistenceUnitBootstrap(new Beans<>(new Pool()), new Beans<>(new Manager()),
                new Beans<TransactionSynchronizationRegistry>(null));

        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> bootstrap.create("orders", null));

        assertTrue(refused.getMessage().contains("no TransactionSynchronizationRegistry bean"), refused.getMessage());
        assertTrue(refused.getMessage().contains("vidocq extension add mansart-transactions"), refused.getMessage());
    }

    @Test
    void a_unit_that_cannot_start_is_named_with_its_pool() {
        var bootstrap = new VidocqPersistenceUnitBootstrap(new Beans<>(new MissingPool()), new Beans<>(new Manager()),
                new Beans<>(new Registry()));

        TransactionIntegration bridge = (synchronization, connections, completion) -> {
            throw new UnsupportedOperationException("a stand-in: the unit never opens an entity manager");
        };

        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> bootstrap.create("ledger", bridge));

        // BUG-20261118-61: the pool's error alone named no unit, and a unit a key declared was hard to tell apart.
        assertTrue(refused.getMessage().startsWith("Persistence unit ledger (JTA, on the default pool)"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains(MissingPool.MESSAGE), refused.getMessage());
    }

    @Test
    void a_unit_is_described_with_the_pool_of_its_transaction_type() {
        assertEquals("JTA, on the pool 'jdbc/DB1'", VidocqPersistenceUnitBootstrap.describe(
                new UnitTransactions(PersistenceUnitTransactionType.JTA, " jdbc/DB1 ", "jdbc/DB_no_tx")));
        assertEquals("RESOURCE_LOCAL, on the pool 'jdbc/DB_no_tx'", VidocqPersistenceUnitBootstrap.describe(
                new UnitTransactions(PersistenceUnitTransactionType.RESOURCE_LOCAL, "jdbc/DB1", "jdbc/DB_no_tx")));
        assertEquals("RESOURCE_LOCAL, on the default pool", VidocqPersistenceUnitBootstrap.describe(
                new UnitTransactions(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, " ")));
    }

    /** One bean, or none: every selection answers the same. */
    private record Beans<T>(T bean) implements Instance<T> {
        @Override public Instance<T> select(Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> type, Annotation... qualifiers) { return cast(); }
        @Override public <U extends T> Instance<U> select(TypeLiteral<U> type, Annotation... qualifiers) { return cast(); }
        @Override public boolean isUnsatisfied() { return bean == null; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public void destroy(T instance) { /* a stand-in: nothing to destroy */ }
        @Override public Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends Handle<T>> handles() { throw new UnsupportedOperationException(); }
        @Override public Iterator<T> iterator() { return bean == null ? Collections.emptyIterator() : List.of(bean).iterator(); }

        @Override
        public T get() {
            if (bean == null) {
                throw new IllegalStateException("unsatisfied");
            }
            return bean;
        }

        @SuppressWarnings("unchecked")
        private <U> Instance<U> cast() {
            return (Instance<U>) this;
        }
    }

    /** The default pool: never asked for a connection, the unit is refused first. */
    private static final class Pool implements DataSource {
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

    /** The default pool of an application without a database, as the Mansart pool extension's holder fails. */
    private static final class MissingPool implements DataSource {
        static final String MESSAGE = "MansartPoolExtension did not publish a DataSource";

        @Override public Connection getConnection() { throw new IllegalStateException(MESSAGE); }
        @Override public Connection getConnection(String user, String password) { throw new IllegalStateException(MESSAGE); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* a stand-in: nothing is logged */ }
        @Override public void setLoginTimeout(int seconds) { /* a stand-in: no timeout */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> type) { throw new IllegalStateException(MESSAGE); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }

    /** A synchronization registry outside any transaction: the unit starts without one. */
    private static final class Registry implements TransactionSynchronizationRegistry {
        @Override public Object getTransactionKey() { return null; }
        @Override public void putResource(Object key, Object value) { throw new UnsupportedOperationException(); }
        @Override public Object getResource(Object key) { throw new UnsupportedOperationException(); }
        @Override public void registerInterposedSynchronization(jakarta.transaction.Synchronization sync) { throw new UnsupportedOperationException(); }
        @Override public int getTransactionStatus() { return jakarta.transaction.Status.STATUS_NO_TRANSACTION; }
        @Override public void setRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override public boolean getRollbackOnly() { throw new UnsupportedOperationException(); }
    }

    /** A transaction manager outside any transaction; the refused units never call it. */
    private static final class Manager implements TransactionManager {
        @Override public void begin() { throw new UnsupportedOperationException(); }
        @Override public void commit() { throw new UnsupportedOperationException(); }
        @Override public int getStatus() { return jakarta.transaction.Status.STATUS_NO_TRANSACTION; }
        @Override public Transaction getTransaction() { return null; }
        @Override public void resume(Transaction transaction) { throw new UnsupportedOperationException(); }
        @Override public void rollback() { throw new UnsupportedOperationException(); }
        @Override public void setRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override public void setTransactionTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public Transaction suspend() { throw new UnsupportedOperationException(); }
    }
}
