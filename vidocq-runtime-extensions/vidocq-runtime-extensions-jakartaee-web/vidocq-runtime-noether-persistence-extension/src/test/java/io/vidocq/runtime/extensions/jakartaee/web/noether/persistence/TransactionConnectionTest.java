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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The handle of an enlisted connection: what it hands to the connection, and what it refuses. */
class TransactionConnectionTest {

    /** A call on the handle, named after the method of the physical connection it must reach. */
    private record Call(String method, SqlCall call) {
        @Override
        public String toString() {
            return method;
        }
    }

    @FunctionalInterface
    private interface SqlCall {
        void on(Connection connection) throws SQLException;
    }

    private static final List<Call> DELEGATED = List.of(
            new Call("rollback", c -> c.rollback(null)),
            new Call("getAutoCommit", Connection::getAutoCommit),
            new Call("createStatement", Connection::createStatement),
            new Call("prepareStatement", c -> c.prepareStatement("select 1")),
            new Call("prepareCall", c -> c.prepareCall("call p()")),
            new Call("nativeSQL", c -> c.nativeSQL("select 1")),
            new Call("getMetaData", Connection::getMetaData),
            new Call("setReadOnly", c -> c.setReadOnly(true)),
            new Call("isReadOnly", Connection::isReadOnly),
            new Call("setCatalog", c -> c.setCatalog("catalog")),
            new Call("getCatalog", Connection::getCatalog),
            new Call("setTransactionIsolation", c -> c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)),
            new Call("getTransactionIsolation", Connection::getTransactionIsolation),
            new Call("getWarnings", Connection::getWarnings),
            new Call("clearWarnings", Connection::clearWarnings),
            new Call("createStatement", c -> c.createStatement(1003, 1007)),
            new Call("prepareStatement", c -> c.prepareStatement("select 1", 1003, 1007)),
            new Call("prepareCall", c -> c.prepareCall("call p()", 1003, 1007)),
            new Call("getTypeMap", Connection::getTypeMap),
            new Call("setTypeMap", c -> c.setTypeMap(Map.of())),
            new Call("setHoldability", c -> c.setHoldability(1)),
            new Call("getHoldability", Connection::getHoldability),
            new Call("setSavepoint", Connection::setSavepoint),
            new Call("setSavepoint", c -> c.setSavepoint("before")),
            new Call("releaseSavepoint", c -> c.releaseSavepoint(null)),
            new Call("createStatement", c -> c.createStatement(1003, 1007, 1)),
            new Call("prepareStatement", c -> c.prepareStatement("select 1", 1003, 1007, 1)),
            new Call("prepareCall", c -> c.prepareCall("call p()", 1003, 1007, 1)),
            new Call("prepareStatement", c -> c.prepareStatement("select 1", Statement.RETURN_GENERATED_KEYS)),
            new Call("prepareStatement", c -> c.prepareStatement("select 1", new int[] {1})),
            new Call("prepareStatement", c -> c.prepareStatement("select 1", new String[] {"id"})),
            new Call("createClob", Connection::createClob),
            new Call("createBlob", Connection::createBlob),
            new Call("createNClob", Connection::createNClob),
            new Call("createSQLXML", Connection::createSQLXML),
            new Call("createArrayOf", c -> c.createArrayOf("int", new Object[0])),
            new Call("createStruct", c -> c.createStruct("point", new Object[0])),
            new Call("getClientInfo", c -> c.getClientInfo("ApplicationName")),
            new Call("getClientInfo", Connection::getClientInfo),
            new Call("setSchema", c -> c.setSchema("schema")),
            new Call("getSchema", Connection::getSchema),
            new Call("setNetworkTimeout", c -> c.setNetworkTimeout(Runnable::run, 1000)),
            new Call("getNetworkTimeout", Connection::getNetworkTimeout),
            new Call("unwrap", c -> c.unwrap(Statement.class)),
            new Call("isWrapperFor", c -> c.isWrapperFor(Statement.class)));

    /** The methods the handle called on the physical connection, in order. */
    private final List<String> reached = new ArrayList<>();

    /** A physical connection inside a transaction: out of auto-commit, recording every call. */
    private final Connection physical = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                reached.add(method.getName());
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return method.getName().equals("isValid");
                }
                return type == int.class ? 0 : null;
            });

    private final TransactionConnection handle = new TransactionConnection(physical);

    @Test
    void anOpenHandleHandsEveryCallToTheConnection() throws SQLException {
        for (Call call : DELEGATED) {
            reached.clear();
            call.call().on(handle);
            assertEquals(List.of(call.method()), reached, "the handle's " + call);
        }
    }

    @Test
    void aClosedHandleRefusesEveryCallWithoutReachingTheConnection() {
        handle.close();

        for (Call call : DELEGATED) {
            SqlCall refusedCall = call.call();
            SQLException refused = assertThrows(SQLException.class, () -> refusedCall.on(handle), call.toString());
            assertTrue(refused.getMessage().contains("closed"), refused.getMessage());
        }
        assertEquals(List.of(), reached);
    }

    @Test
    void aLocalCommitOrRollbackIsRefusedAndNamed() {
        SQLException commit = assertThrows(SQLException.class, handle::commit);
        SQLException rollback = assertThrows(SQLException.class, handle::rollback);

        assertTrue(commit.getMessage().startsWith("A local commit is refused"), commit.getMessage());
        assertTrue(rollback.getMessage().startsWith("A local rollback is refused"), rollback.getMessage());
        assertEquals(List.of(), reached, "the connection completes with the transaction, not on these calls");
    }

    @Test
    void theAutoCommitModeOfTheTransactionIsKeptAndNoOtherIsTaken() throws SQLException {
        handle.setAutoCommit(false);

        SQLException refused = assertThrows(SQLException.class, () -> handle.setAutoCommit(true));
        assertTrue(refused.getMessage().startsWith("A local switch of auto-commit is refused"), refused.getMessage());
        assertEquals(List.of("getAutoCommit", "getAutoCommit"), reached, "the mode is read, never set");
    }

    @Test
    void closingOrAbortingTheHandleLeavesTheConnectionToTheTransaction() {
        handle.close();
        TransactionConnection aborted = new TransactionConnection(physical);
        aborted.abort(Runnable::run);

        assertTrue(handle.isClosed());
        assertTrue(aborted.isClosed());
        assertEquals(List.of(), reached);
    }

    @Test
    void anOpenHandleIsValidWhileItsConnectionIsAndAClosedOneIsNot() throws SQLException {
        assertTrue(handle.isValid(1));
        assertEquals(List.of("isValid"), reached);

        handle.close();
        assertFalse(handle.isValid(1));
        assertEquals(List.of("isValid"), reached, "a closed handle does not ask the connection");
    }

    @Test
    void theHandleUnwrapsToItselfBeforeTheConnection() throws SQLException {
        assertSame(handle, handle.unwrap(TransactionConnection.class));
        assertTrue(handle.isWrapperFor(Connection.class));
        assertEquals(List.of(), reached);
    }

    @Test
    void clientInfoGoesToTheConnection() throws SQLException {
        handle.setClientInfo("ApplicationName", "ledger");
        handle.setClientInfo(new Properties());

        assertEquals(List.of("setClientInfo", "setClientInfo"), reached);
    }
}
