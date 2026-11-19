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
package io.vidocq.mansart.transactions.it;

import io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
import io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer;
import io.vidocq.mansart.transactions.jdbc.JtaConnections;
import io.vidocq.mansart.transactions.jdbc.JtaDataSource;
import io.vidocq.vauban.junit.AddBeans;
import io.vidocq.vauban.junit.VaubanTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One connection per transaction and per data source, whoever asks for it: the handles of a {@link JtaDataSource}
 * and the connections {@link JtaConnections} hands to a bridge are the same physical connection, enlisted once.
 */
@VaubanTest
@AddBeans({MansartTransactionsProducer.class, MansartTransactionsExtension.class})
class JtaDataSourceTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Inject TransactionManager tm;
    @Inject TransactionSynchronizationRegistry registry;

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            tm.rollback();
        }
    }

    private RecordingDataSource database(boolean xa) throws SQLException {
        RecordingDataSource pool = new RecordingDataSource("jta-ds-" + DATABASES.incrementAndGet(), xa);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE notes(id INT PRIMARY KEY, body VARCHAR(50))");
        }
        pool.plain.clear();
        return pool;
    }

    private JtaDataSource jta(DataSource pool) {
        return new JtaDataSource(pool, tm, registry);
    }

    private static void insert(Connection connection, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO notes VALUES (" + id + ", 'note')");
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM notes")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** The rows another connection sees, once everything is committed. */
    private static int committed(RecordingDataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return count(connection);
        }
    }

    @Test
    void outside_a_transaction_a_connection_is_the_pools_own() throws Exception {
        RecordingDataSource pool = database(false);
        Connection physical;
        try (Connection connection = jta(pool).getConnection()) {
            physical = connection;
            assertThat(connection.getAutoCommit()).isTrue();
            insert(connection, 1);
        }
        assertThat(pool.plain).containsExactly(physical);
        assertThat(physical.isClosed()).isTrue();
        assertThat(committed(pool)).isEqualTo(1);
    }

    @Test
    void two_calls_in_a_transaction_share_one_connection() throws Exception {
        RecordingDataSource pool = database(false);
        JtaDataSource dataSource = jta(pool);
        tm.begin();
        try (Connection first = dataSource.getConnection()) {
            insert(first, 1);
        }
        try (Connection second = dataSource.getConnection()) {
            assertThat(count(second)).isEqualTo(1);
        }
        assertThat(pool.plain).hasSize(1);
        assertThat(pool.plain.getFirst().isClosed()).isFalse();
        tm.commit();
        assertThat(pool.plain.getFirst().isClosed()).isTrue();
        assertThat(committed(pool)).isEqualTo(1);
    }

    @Test
    void a_rollback_discards_the_work_of_every_handle() throws Exception {
        RecordingDataSource pool = database(false);
        JtaDataSource dataSource = jta(pool);
        tm.begin();
        try (Connection first = dataSource.getConnection()) {
            insert(first, 1);
        }
        try (Connection second = dataSource.getConnection()) {
            insert(second, 2);
        }
        tm.rollback();
        assertThat(pool.plain.getFirst().isClosed()).isTrue();
        assertThat(committed(pool)).isZero();
    }

    @Test
    void a_bridge_and_a_handle_share_the_connection_of_the_transaction() throws Exception {
        RecordingDataSource pool = database(false);
        JtaConnections bridge = new JtaConnections(tm, registry);
        tm.begin();
        insert(bridge.enlisted(pool), 1);
        try (Connection handle = jta(pool).getConnection()) {
            assertThat(count(handle)).isEqualTo(1);
            insert(handle, 2);
        }
        assertThat(count(bridge.enlisted(pool))).isEqualTo(2);
        assertThat(pool.plain).hasSize(1);
        tm.commit();
        assertThat(committed(pool)).isEqualTo(2);
    }

    @Test
    void a_bridge_given_the_jta_data_source_takes_the_connection_of_its_pool() throws Exception {
        RecordingDataSource pool = database(false);
        JtaDataSource dataSource = jta(pool);
        JtaConnections bridge = new JtaConnections(tm, registry);
        tm.begin();
        Connection viaWrapper = bridge.enlisted(dataSource);
        Connection viaPool = bridge.enlisted(pool);
        assertThat(viaWrapper).isSameAs(viaPool);
        assertThat(pool.plain).hasSize(1);
    }

    @Test
    void a_bridge_has_no_connection_outside_a_transaction() throws Exception {
        RecordingDataSource pool = database(false);
        assertThat(new JtaConnections(tm, registry).enlisted(pool)).isNull();
        assertThat(pool.plain).isEmpty();
    }

    @Test
    void an_xa_capable_pool_is_enlisted_through_its_xa_resource() throws Exception {
        RecordingDataSource pool = database(true);
        JtaDataSource dataSource = jta(pool);
        tm.begin();
        try (Connection first = dataSource.getConnection()) {
            insert(first, 1);
        }
        try (Connection second = dataSource.getConnection()) {
            assertThat(count(second)).isEqualTo(1);
        }
        assertThat(pool.xaConnections).hasSize(1);
        assertThat(pool.plain).isEmpty();
        tm.commit();
        assertThat(pool.xaPhysical.getFirst().isClosed()).isTrue();
        assertThat(committed(pool)).isEqualTo(1);
    }

    @Test
    void a_suspended_transaction_keeps_its_connection_apart() throws Exception {
        RecordingDataSource pool = database(false);
        JtaDataSource dataSource = jta(pool);
        tm.begin();
        try (Connection outer = dataSource.getConnection()) {
            insert(outer, 1);
        }
        Transaction suspended = tm.suspend();
        tm.begin();
        try (Connection inner = dataSource.getConnection()) {
            assertThat(count(inner)).isZero();
            insert(inner, 2);
        }
        tm.commit();
        tm.resume(suspended);
        assertThat(pool.plain).hasSize(2);
        try (Connection outer = dataSource.getConnection()) {
            assertThat(count(outer)).isEqualTo(2);
        }
        tm.rollback();
        assertThat(committed(pool)).isEqualTo(1);
    }

    @Test
    void two_data_sources_have_a_connection_each() throws Exception {
        RecordingDataSource first = database(false);
        RecordingDataSource second = database(false);
        tm.begin();
        try (Connection one = jta(first).getConnection(); Connection two = jta(second).getConnection()) {
            insert(one, 1);
            insert(two, 1);
        }
        tm.commit();
        assertThat(first.plain).hasSize(1);
        assertThat(second.plain).hasSize(1);
        assertThat(committed(first)).isEqualTo(1);
        assertThat(committed(second)).isEqualTo(1);
    }

    @Test
    void a_handle_leaves_completion_to_the_transaction_manager() throws Exception {
        JtaDataSource dataSource = jta(database(false));
        tm.begin();
        Connection handle = dataSource.getConnection();
        assertThat(handle.getAutoCommit()).isFalse();
        handle.setAutoCommit(false);
        assertThatThrownBy(handle::commit).isInstanceOf(SQLException.class).hasMessageContaining("JTA transaction");
        assertThatThrownBy(handle::rollback).isInstanceOf(SQLException.class).hasMessageContaining("JTA transaction");
        assertThatThrownBy(() -> handle.setAutoCommit(true))
                .isInstanceOf(SQLException.class).hasMessageContaining("JTA transaction");
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
    }

    @Test
    void a_closed_handle_refuses_work_and_leaves_the_others_alone() throws Exception {
        JtaDataSource dataSource = jta(database(false));
        tm.begin();
        Connection closed = dataSource.getConnection();
        Connection open = dataSource.getConnection();
        closed.close();
        assertThat(closed.isClosed()).isTrue();
        assertThatThrownBy(closed::createStatement).isInstanceOf(SQLException.class).hasMessageContaining("closed");
        assertThat(open.isClosed()).isFalse();
        insert(open, 1);
        assertThat(count(open)).isEqualTo(1);
    }

    @Test
    void a_handle_kept_after_the_transaction_refuses_work() throws Exception {
        JtaDataSource dataSource = jta(database(false));
        tm.begin();
        Connection handle = dataSource.getConnection();
        tm.commit();
        assertThat(handle.isClosed()).isTrue();
        assertThatThrownBy(handle::createStatement).isInstanceOf(SQLException.class).hasMessageContaining("closed");
    }

    @Test
    void credentials_of_a_call_are_refused_in_a_transaction_and_passed_on_outside() throws Exception {
        RecordingDataSource pool = database(false);
        JtaDataSource dataSource = jta(pool);
        try (Connection connection = dataSource.getConnection("sa", "")) {
            assertThat(connection.getAutoCommit()).isTrue();
        }
        tm.begin();
        assertThatThrownBy(() -> dataSource.getConnection("sa", ""))
                .isInstanceOf(SQLFeatureNotSupportedException.class);
    }

    @Test
    void a_failed_enlistment_gives_the_connection_back() throws Exception {
        RecordingDataSource pool = database(false);
        JtaDataSource dataSource = jta(pool);
        tm.begin();
        tm.setRollbackOnly();
        assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
        assertThat(pool.plain).allSatisfy(connection -> assertThat(connection.isClosed()).isTrue());
    }

    @Test
    void the_wrapper_answers_for_itself_and_for_its_pool() throws Exception {
        RecordingDataSource pool = database(true);
        JtaDataSource dataSource = jta(pool);
        assertThat(dataSource.isWrapperFor(JtaDataSource.class)).isTrue();
        assertThat(dataSource.unwrap(JtaDataSource.class)).isSameAs(dataSource);
        assertThat(dataSource.isWrapperFor(XADataSource.class)).isTrue();
        assertThat(dataSource.unwrap(XADataSource.class)).isNotNull();
        assertThat(dataSource.unwrap(RecordingDataSource.class)).isSameAs(pool);
        assertThat(dataSource.getLoginTimeout()).isZero();
        assertThat(dataSource.getParentLogger()).isNotNull();
        assertThat(dataSource.getLogWriter()).isNull();
        dataSource.setLoginTimeout(0);
        dataSource.setLogWriter(null);
    }
}
