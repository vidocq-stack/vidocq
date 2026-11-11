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
package io.vidocq.mansart.pool.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A session attribute a borrower can change through the JDBC {@link Connection} API and that outlives the borrow,
 * so the pool puts it back on release (MANSART-047). Auto-commit is not one of them: the release handles it with the
 * pending work. Client info and sharding keys are left as the borrower set them.
 */
enum SessionAttribute {

    TRANSACTION_ISOLATION(Connection::getTransactionIsolation, (c, v) -> c.setTransactionIsolation((Integer) v)),
    READ_ONLY(Connection::isReadOnly, (c, v) -> c.setReadOnly((Boolean) v)),
    CATALOG(Connection::getCatalog, (c, v) -> c.setCatalog((String) v)),
    SCHEMA(Connection::getSchema, (c, v) -> c.setSchema((String) v)),
    HOLDABILITY(Connection::getHoldability, (c, v) -> c.setHoldability((Integer) v)),
    NETWORK_TIMEOUT(Connection::getNetworkTimeout, SessionAttribute::writeNetworkTimeout),
    TYPE_MAP(c -> copy(c.getTypeMap()), (c, v) -> c.setTypeMap(copy(v)));

    /** Reads the attribute from the driver's connection. */
    @FunctionalInterface
    interface Read {
        Object from(Connection driver) throws SQLException;
    }

    /** Sets the attribute on the driver's connection. */
    @FunctionalInterface
    interface Write {
        void to(Connection driver, Object value) throws SQLException;
    }

    private final Read  read;
    private final Write write;

    SessionAttribute(Read read, Write write) {
        this.read  = read;
        this.write = write;
    }

    Object read(Connection driver) throws SQLException {
        return read.from(driver);
    }

    void write(Connection driver, Object value) throws SQLException {
        write.to(driver, value);
    }

    /** The bit of this attribute in a set of attributes. */
    int bit() {
        return 1 << ordinal();
    }

    /**
     * The executor a driver uses to abort a connection whose network timeout expires: a virtual thread per abort.
     * The borrower's own executor may be gone by the time the pool puts the timeout back.
     */
    private static void writeNetworkTimeout(Connection driver, Object milliseconds) throws SQLException {
        Executor abort = task -> Thread.ofVirtual().name("mansart-pool-abort").start(task);
        driver.setNetworkTimeout(abort, (Integer) milliseconds);
    }

    /** A private copy, so the driver's map and the pool's never share a mutable instance. */
    @SuppressWarnings("unchecked")
    private static Map<String, Class<?>> copy(Object typeMap) {
        return typeMap == null ? new HashMap<>() : new HashMap<>((Map<String, Class<?>>) typeMap);
    }
}
