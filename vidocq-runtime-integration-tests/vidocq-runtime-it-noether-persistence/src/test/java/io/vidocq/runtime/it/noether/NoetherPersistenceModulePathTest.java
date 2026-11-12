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
package io.vidocq.runtime.it.noether;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoetherPersistenceModulePathTest {

    private static VidocqBootstrap bootstrap;

    @BeforeAll
    static void boot() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    private static Ledger ledger() {
        return CDI.current().select(Ledger.class).get();
    }

    @Test
    void a_transactional_method_commits_what_it_persists() {
        ledger().record("committed");

        assertEquals(1, ledger().count("committed"));
    }

    @Test
    void a_transactional_method_that_throws_rolls_back_what_it_persisted() {
        assertThrows(IllegalStateException.class, () -> ledger().recordThenFail("rolled-back"));

        assertEquals(0, ledger().count("rolled-back"));
    }

    @Test
    void the_persistence_context_keeps_one_instance_per_entity_within_a_transaction() {
        assertTrue(ledger().findsWhatItPersistedWithinOneTransaction("identity"));
    }

    @Test
    void an_unsynchronized_persistence_context_writes_only_once_joined() throws Exception {
        assertFalse(ledger().draftWithoutJoining("unjoined"), "joined without joinTransaction");
        assertEquals(0, ledger().count("unjoined"));

        ledger().draftThenJoin("joined");
        assertEquals(1, ledger().count("joined"));
    }

    @Test
    void the_container_owns_the_injected_factory() {
        assertThrows(IllegalStateException.class, () -> ledger().factory().close());
        assertTrue(ledger().factory().isOpen());
        assertEquals("ledger", ledger().factory().getName());
    }

    @Test
    void the_unit_writes_to_the_database_of_the_default_pool() throws SQLException {
        ledger().record("pooled");

        DataSource pool = CDI.current().select(DataSource.class).get();
        try (var connection = pool.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                     + " WHERE UPPER(TABLE_NAME) = 'ENTRY'")) {
            rows.next();
            assertEquals(1, rows.getLong(1), "the unit created its table in vidocq.pool.url's database");
        }
    }
}
