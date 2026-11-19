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
package io.vidocq.runtime.it.noether.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Vidocq/mansart#103: within one JTA transaction, a persistence unit and a Jakarta Data repository on the same pool
 * work on one connection. Each sees what the other wrote before the commit, and a rollback undoes both.
 */
class SharedConnectionModulePathTest {

    private static VidocqBootstrap bootstrap;

    @BeforeAll
    static void boot() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
        try (Connection connection = pool().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ITEM");
            statement.execute("CREATE TABLE ITEM (ID VARCHAR(64) PRIMARY KEY, LABEL VARCHAR(64))");
        }
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    private static DataSource pool() {
        return CDI.current().select(DataSource.class).get();
    }

    private static Workbench workbench() {
        return CDI.current().select(Workbench.class).get();
    }

    @Test
    void a_repository_finds_what_the_unit_flushed_in_the_same_transaction() throws SQLException {
        assertTrue(workbench().repositoryFindsWhatTheUnitFlushed("unit-first"),
                "the repository read on the unit's connection, before the commit");
        assertEquals(1, committed("unit-first"));
    }

    @Test
    void the_unit_finds_what_a_repository_saved_in_the_same_transaction() throws SQLException {
        assertTrue(workbench().unitFindsWhatTheRepositorySaved("repository-first"),
                "the unit read on the repository's connection, before the commit");
        assertEquals(1, committed("repository-first"));
    }

    @Test
    void a_rollback_undoes_the_writes_of_both() throws SQLException {
        Workbench workbench = workbench();

        assertThrows(IllegalStateException.class, () -> workbench.bothWriteThenFail("unit-undone", "repository-undone"));

        assertEquals(0, committed("unit-undone"));
        assertEquals(0, committed("repository-undone"));
    }

    /** What a connection outside any transaction sees. */
    private static long committed(String id) throws SQLException {
        try (Connection connection = pool().getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM ITEM WHERE ID = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
