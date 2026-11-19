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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The boot-time recovery scan opening one XAConnection per XA-capable data source. */
class RecoveryScanTest {

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(RecoveryScanTest.class.getClassLoader(), new Class<?>[] {type},
                handler));
    }

    @Test
    void anOpenedConnectionAndItsResourceJoinTheScan() {
        XAResource resource = stub(XAResource.class, (_, _, _) -> null);
        XAConnection connection = stub(XAConnection.class,
                (_, method, _) -> "getXAResource".equals(method.getName()) ? resource : null);
        XADataSource dataSource = stub(XADataSource.class,
                (_, method, _) -> "getXAConnection".equals(method.getName()) ? connection : null);
        List<XAConnection> connections = new ArrayList<>();
        List<XAResource> resources = new ArrayList<>();

        MansartTransactionsIntegrationExtension.openForRecovery(dataSource, "orders", connections, resources);

        assertEquals(1, connections.size());
        assertSame(connection, connections.getFirst());
        assertEquals(1, resources.size());
        assertSame(resource, resources.getFirst());
    }

    @Test
    void aConnectionThatCannotOpenLeavesTheScanGoingOn() {
        XADataSource dataSource = stub(XADataSource.class, (_, _, _) -> {
            throw new SQLException("database down");
        });
        List<XAConnection> connections = new ArrayList<>();
        List<XAResource> resources = new ArrayList<>();

        MansartTransactionsIntegrationExtension.openForRecovery(dataSource, "orders", connections, resources);

        assertTrue(connections.isEmpty());
        assertTrue(resources.isEmpty());
    }
}
