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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot's read of a pool's tables (SQL spec §4): its foreign keys read once per schema where the driver accepts
 * it, within a time budget, on a read-only connection.
 */
class PoolMetadataTest {

    private final TestPools pools = TestPools.withTables();

    @AfterEach
    void stop() {
        pools.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object call(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    /** {@code connection} recording what the test asks: the {@code getImportedKeys} tables, the read-only flags. */
    private static Connection recording(Connection connection, List<String> events) {
        return proxy(Connection.class, (self, method, arguments) -> {
            if (method.getName().equals("setReadOnly")) {
                events.add("readOnly=" + arguments[0]);
            }
            if (method.getName().equals("getMetaData")) {
                DatabaseMetaData real = connection.getMetaData();
                return proxy(DatabaseMetaData.class, (meta, asked, values) -> {
                    if (asked.getName().equals("getImportedKeys")) {
                        events.add("keys:" + values[1] + "." + values[2]);
                    }
                    if (asked.getName().equals("getTables")) {
                        events.add("tables");
                    }
                    return call(asked, real, values);
                });
            }
            return call(method, connection, arguments);
        });
    }

    private static String target(PoolMetadata metadata, String table, String column) {
        Table found = metadata.tables().stream().filter(t -> t.name().equals(table)).findFirst().orElseThrow();
        return found.columns().stream().filter(c -> c.name().equals(column)).findFirst().orElseThrow().target();
    }

    /**
     * {@code connection}, whose {@code getImportedKeys} with no table answers every key of the schema, as PostgreSQL
     * does (H2 refuses it), and records each call.
     */
    private static Connection schemaWide(Connection connection, List<String> events) {
        return proxy(Connection.class, (self, method, arguments) -> {
            if (!method.getName().equals("getMetaData")) {
                return call(method, connection, arguments);
            }
            DatabaseMetaData real = connection.getMetaData();
            return proxy(DatabaseMetaData.class, (meta, asked, values) -> {
                if (!asked.getName().equals("getImportedKeys")) {
                    return call(asked, real, values);
                }
                events.add("keys:" + values[1] + "." + values[2]);
                if (values[2] != null) {
                    return call(asked, real, values);
                }
                // what such a driver answers: the keys of every table of the schema, in one result
                org.h2.tools.SimpleResultSet all = new org.h2.tools.SimpleResultSet();
                for (String column : List.of("PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_SCHEM",
                        "FKTABLE_NAME", "FKCOLUMN_NAME", "FK_NAME")) {
                    all.addColumn(column, java.sql.Types.VARCHAR, 0, 0);
                }
                List<String> names = new ArrayList<>();
                try (java.sql.ResultSet tables = real.getTables(values[0] == null ? null : (String) values[0],
                        (String) values[1], "%", null)) {
                    while (tables.next()) {
                        names.add(tables.getString("TABLE_NAME"));
                    }
                }
                for (String name : names) {
                    try (java.sql.ResultSet keys = real.getImportedKeys((String) values[0], (String) values[1], name)) {
                        while (keys.next()) {
                            all.addRow(keys.getString("PKTABLE_SCHEM"), keys.getString("PKTABLE_NAME"),
                                    keys.getString("PKCOLUMN_NAME"), keys.getString("FKTABLE_SCHEM"),
                                    keys.getString("FKTABLE_NAME"), keys.getString("FKCOLUMN_NAME"),
                                    keys.getString("FK_NAME"));
                        }
                    }
                }
                return all;
            });
        });
    }

    @Test
    void theForeignKeysAreReadOnceForEachSchemaNotOncePerTable() throws SQLException {
        List<String> events = new ArrayList<>();
        try (Connection connection = pools.pool("@Default").getConnection()) {
            PoolMetadata metadata = PoolMetadata.read(schemaWide(connection, events), true);

            assertEquals("projects", target(metadata, "tasks", "project_id"));
            assertEquals(List.of("keys:public.null", "keys:sales.null"), events.stream().sorted().toList(),
                    "a call with no table, per schema");
        }
    }

    @Test
    void aDriverThatRefusesTheSchemaWideCallIsAskedTableByTable() throws SQLException {
        List<String> events = new ArrayList<>();
        try (Connection connection = pools.pool("@Default").getConnection()) {
            Connection refusing = proxy(Connection.class, (self, method, arguments) -> {
                if (!method.getName().equals("getMetaData")) {
                    return call(method, connection, arguments);
                }
                DatabaseMetaData real = connection.getMetaData();
                return proxy(DatabaseMetaData.class, (meta, asked, values) -> {
                    if (asked.getName().equals("getImportedKeys")) {
                        events.add("keys:" + values[2]);
                        if (values[2] == null) {
                            throw new SQLException("a table is required");
                        }
                    }
                    return call(asked, real, values);
                });
            });

            PoolMetadata metadata = PoolMetadata.read(refusing, true);

            assertEquals("projects", target(metadata, "tasks", "project_id"), "found all the same");
            assertTrue(events.contains("keys:tasks"), "one call per table after the refusal: " + events);
        }
    }

    @Test
    void aTimeBudgetSpentLeavesTheTablesAndNoReference() throws SQLException {
        try (Connection connection = pools.pool("@Default").getConnection()) {
            PoolMetadata metadata = PoolMetadata.read(connection, true, Duration.ZERO);

            assertEquals(4, metadata.tables().size(), "every table is listed");
            assertNull(target(metadata, "tasks", "project_id"), "no time was left for its foreign keys");
        }
    }

    @Test
    void theBootReadsOnAReadOnlyConnectionGivenBackAsItWas() throws SQLException {
        List<String> events = new ArrayList<>();
        try (Connection connection = pools.pool("@Default").getConnection()) {
            PoolMetadata metadata = PoolMetadata.readOnly(recording(connection, events));

            assertEquals("projects", target(metadata, "tasks", "project_id"));
            assertEquals("readOnly=true", events.getFirst(), "before anything is read: " + events);
            assertEquals("readOnly=false", events.getLast(), "and as it was after");
        }
    }
}
