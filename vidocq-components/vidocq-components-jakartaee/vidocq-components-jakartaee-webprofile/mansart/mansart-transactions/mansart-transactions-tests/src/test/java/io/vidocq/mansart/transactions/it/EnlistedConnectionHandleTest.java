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
import io.vidocq.mansart.transactions.jdbc.JtaDataSource;
import io.vidocq.vauban.junit.AddBeans;
import io.vidocq.vauban.junit.VaubanTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The handle of a {@link JtaDataSource} within a transaction passes the work on to the connection of the
 * transaction while it is open, and refuses it once closed.
 */
@VaubanTest
@AddBeans({MansartTransactionsProducer.class, MansartTransactionsExtension.class})
class EnlistedConnectionHandleTest {

    private static final String SQL = "SELECT 1";
    private static final String CLOSED_STATE = "08003";

    @FunctionalInterface
    private interface Call {
        void on(Connection connection) throws SQLException;
    }

    @Inject TransactionManager tm;
    @Inject TransactionSynchronizationRegistry registry;

    private RecordingDataSource pool;
    private Connection handle;

    @BeforeEach
    void open() throws Exception {
        pool = new RecordingDataSource("jta-handle", false);
        tm.begin();
        handle = new JtaDataSource(pool, tm, registry).getConnection();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            tm.rollback();
        }
    }

    /** Every method of the handle that works on the connection, by name. */
    private static Map<String, Call> work() {
        Map<String, Call> calls = new LinkedHashMap<>();
        calls.put("createStatement", c -> c.createStatement().close());
        calls.put("createStatement(type, concurrency)",
                c -> c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).close());
        calls.put("createStatement(type, concurrency, holdability)", c -> c.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT).close());
        calls.put("prepareStatement", c -> c.prepareStatement(SQL).close());
        calls.put("prepareStatement(type, concurrency)",
                c -> c.prepareStatement(SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).close());
        calls.put("prepareStatement(type, concurrency, holdability)", c -> c.prepareStatement(SQL,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT).close());
        calls.put("prepareStatement(keys)", c -> c.prepareStatement(SQL, Statement.NO_GENERATED_KEYS).close());
        calls.put("prepareStatement(indexes)", c -> c.prepareStatement(SQL, new int[] {1}).close());
        calls.put("prepareStatement(names)", c -> c.prepareStatement(SQL, new String[] {"ID"}).close());
        calls.put("prepareCall", c -> c.prepareCall(SQL).close());
        calls.put("prepareCall(type, concurrency)",
                c -> c.prepareCall(SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).close());
        calls.put("prepareCall(type, concurrency, holdability)", c -> c.prepareCall(SQL,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT).close());
        calls.put("nativeSQL", c -> c.nativeSQL(SQL));
        calls.put("getMetaData", Connection::getMetaData);
        calls.put("getAutoCommit", Connection::getAutoCommit);
        calls.put("setAutoCommit(same)", c -> c.setAutoCommit(false));
        calls.put("isReadOnly", Connection::isReadOnly);
        calls.put("setReadOnly", c -> c.setReadOnly(false));
        calls.put("getCatalog", Connection::getCatalog);
        calls.put("setCatalog", c -> c.setCatalog(c.getCatalog()));
        calls.put("getSchema", Connection::getSchema);
        calls.put("setSchema", c -> c.setSchema(c.getSchema()));
        calls.put("getTransactionIsolation", Connection::getTransactionIsolation);
        calls.put("setTransactionIsolation", c -> c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED));
        calls.put("getWarnings", Connection::getWarnings);
        calls.put("clearWarnings", Connection::clearWarnings);
        calls.put("getTypeMap", Connection::getTypeMap);
        calls.put("setTypeMap", c -> c.setTypeMap(Map.of()));
        calls.put("getHoldability", Connection::getHoldability);
        calls.put("setHoldability", c -> c.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        calls.put("savepoints", c -> {
            Savepoint unnamed = c.setSavepoint();
            c.rollback(unnamed);
            c.releaseSavepoint(c.setSavepoint("named"));
        });
        calls.put("createClob", c -> c.createClob().free());
        calls.put("createBlob", c -> c.createBlob().free());
        calls.put("createNClob", c -> c.createNClob().free());
        calls.put("createSQLXML", c -> c.createSQLXML().free());
        calls.put("createArrayOf", c -> c.createArrayOf("INTEGER", new Object[] {1}).free());
        calls.put("createStruct", c -> c.createStruct("POINT", new Object[] {1}));
        calls.put("getClientInfo", Connection::getClientInfo);
        calls.put("getClientInfo(name)", c -> c.getClientInfo("ApplicationName"));
        calls.put("getNetworkTimeout", Connection::getNetworkTimeout);
        calls.put("setNetworkTimeout", c -> c.setNetworkTimeout(Runnable::run, 0));
        calls.put("unwrap", c -> c.unwrap(org.h2.jdbc.JdbcConnection.class));
        calls.put("isWrapperFor", c -> c.isWrapperFor(org.h2.jdbc.JdbcConnection.class));
        calls.put("commit", Connection::commit);
        calls.put("rollback", Connection::rollback);
        return calls;
    }

    /** The SQLSTATE of the refusal, or {@code null} when the call went through. */
    private static String refusal(Call call, Connection connection) {
        try {
            call.on(connection);
            return null;
        } catch (SQLException e) {
            return String.valueOf(e.getSQLState());
        }
    }

    @Test
    void an_open_handle_never_answers_that_it_is_closed() {
        Map<String, String> closedAnswers = new LinkedHashMap<>();
        work().forEach((name, call) -> {
            String state = refusal(call, handle);
            if (CLOSED_STATE.equals(state)) closedAnswers.put(name, state);
        });
        assertThat(closedAnswers).isEmpty();
    }

    @Test
    void a_closed_handle_refuses_every_piece_of_work() throws Exception {
        handle.close();
        Map<String, String> answers = new LinkedHashMap<>();
        work().forEach((name, call) -> answers.put(name, refusal(call, handle)));
        assertThat(answers).allSatisfy((name, state) -> assertThat(state).as(name).isEqualTo(CLOSED_STATE));
    }

    @Test
    void the_handle_reaches_the_connection_of_the_transaction() throws Exception {
        assertThat(handle.unwrap(Connection.class)).isSameAs(handle);
        assertThat(handle.isWrapperFor(Connection.class)).isTrue();
        assertThat(handle.unwrap(org.h2.jdbc.JdbcConnection.class)).isSameAs(pool.plain.getFirst());
        assertThat(handle.isValid(1)).isTrue();
        assertThat(handle.nativeSQL(SQL)).isEqualTo(SQL);
        assertThat(handle.getMetaData().getDatabaseProductName()).isEqualTo("H2");
    }

    @Test
    void client_info_goes_to_the_connection() throws Exception {
        handle.setClientInfo(new Properties());
        // H2 knows no client info name: its refusal shows the call reached the driver.
        assertThatThrownBy(() -> handle.setClientInfo("ApplicationName", "mansart"))
                .isInstanceOf(SQLClientInfoException.class)
                .hasMessageContaining("ApplicationName");
        assertThat(handle.getClientInfo("ApplicationName")).isNull();
    }

    @Test
    void an_aborted_handle_is_closed_and_invalid() throws Exception {
        handle.abort(Runnable::run);
        assertThat(handle.isClosed()).isTrue();
        assertThat(handle.isValid(1)).isFalse();
        assertThat(pool.plain.getFirst().isClosed()).isFalse();
    }
}
