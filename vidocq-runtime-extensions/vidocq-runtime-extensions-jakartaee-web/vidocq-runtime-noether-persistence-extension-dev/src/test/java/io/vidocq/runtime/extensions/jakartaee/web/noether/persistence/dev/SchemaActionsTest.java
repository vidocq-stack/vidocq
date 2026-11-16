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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.SchemaScripts;
import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Index;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Table;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.IndexDiff.Found;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Indexes and DDL tabs, on an H2 database of the shape the unit declares, give or take an index. */
class SchemaActionsTest {

    static final Table NOTE = new Table("note", List.of(), List.of("id"), List.of(
            new Index("idx_note_title", List.of("title"), false),
            new Index("idx_note_due", List.of("due"), false),
            new Index(null, List.of("code"), true)), List.of());

    static final UnitDescription NOTES = new UnitDescription("notes", List.of(), List.of(), List.of(),
            List.of(NOTE));

    private final List<String> calls = new ArrayList<>();
    private Connection connection;

    @BeforeEach
    void database() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:indexes;DB_CLOSE_DELAY=-1");
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE NOTE (ID BIGINT PRIMARY KEY, TITLE VARCHAR(80), DUE DATE, CODE VARCHAR(8),"
                    + " CONSTRAINT UK_NOTE_CODE UNIQUE (CODE))");
            ddl.execute("CREATE INDEX IDX_NOTE_TITLE ON NOTE (TITLE)");
            ddl.execute("CREATE INDEX IDX_STRAY ON NOTE (DUE, TITLE)");
        }
    }

    @AfterEach
    void drop() throws SQLException {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("DROP ALL OBJECTS");
        }
        connection.close();
    }

    private Unit unit(SchemaScripts scripts) {
        EntityTransaction transaction = Stubs.of(EntityTransaction.class, calls, Map.of("begin", Stubs.NOTHING,
                "rollback", Stubs.NOTHING, "isActive", args -> false));
        EntityManager em = Stubs.of(EntityManager.class, calls, Map.of(
                "getTransaction", args -> transaction,
                "unwrap", args -> connection,
                "close", Stubs.NOTHING));
        return new Unit("notes", false, null, "none", new StubFactory("notes", NOTES, scripts, () -> em));
    }

    @Test
    void theDatabaseIndexesOfATableAreReadWhateverTheCaseItIsDeclaredIn() throws SQLException {
        List<Found> found = DatabaseIndexes.read(connection, List.of(NOTE));

        assertTrue(found.contains(new Found("note", "IDX_NOTE_TITLE", List.of("TITLE"), false)), found.toString());
        assertTrue(found.contains(new Found("note", "IDX_STRAY", List.of("DUE", "TITLE"), false)), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.columns().equals(List.of("CODE")) && f.unique()),
                found.toString());
    }

    @Test
    void theDeclaredIndexesAreReadFromMemory() {
        ActionResult result = new IndexActions(new UnitWork(() -> null)).declaredIndexes(unit(null));

        assertEquals("3 indexes on 1 table", result.summary());
        assertTrue(result.body().contains("[\"note\",\"idx_note_title\",\"title\",\"no\"]"), result.body());
        assertTrue(result.body().contains("[\"note\",\"\",\"code\",\"yes\"]"), result.body());
        assertEquals(List.of(), calls);
    }

    @Test
    void theComparisonReadsTheDatabaseInATransactionRolledBack() {
        ActionResult result = new IndexActions(new UnitWork(() -> null)).compareIndexes(unit(null));

        assertFalse(result.error(), result.summary());
        assertEquals("2 same, 1 missing in the database, 1 extra in the database", result.summary());
        assertTrue(result.body().contains("[\"note\",\"idx_note_due\",\"(due)\",\"\",\"missing in the database\"]"),
                result.body());
        assertTrue(result.body().contains("\"IDX_STRAY\""), result.body());
        assertTrue(calls.contains("rollback"));
    }

    @Test
    void theTabsAreTheirGroups() {
        PanelAction.Argument unit = new PanelAction.Argument("unit", "Persistence unit", List.of("notes"), null,
                null);
        UnitWork work = new UnitWork(() -> null);

        List<PanelAction> actions = new ArrayList<>(new IndexActions(work).actions(unit, call -> given -> null));
        actions.addAll(new DdlActions().actions(unit, call -> given -> null));

        assertEquals(List.of("indexes.declared", "indexes.compare", "ddl.create", "ddl.drop"),
                actions.stream().map(PanelAction::id).toList());
        assertEquals(List.of("Indexes", "Indexes", "DDL", "DDL"), actions.stream().map(PanelAction::group).toList());
    }

    @Test
    void theScriptsAreSqlEachStatementEndedOnItsLine() {
        SchemaScripts scripts = new SchemaScripts() {
            @Override
            public List<String> createScript() {
                return List.of("CREATE TABLE NOTE (ID BIGINT)", "CREATE INDEX IDX_NOTE_TITLE ON NOTE (TITLE)");
            }

            @Override
            public List<String> dropScript() {
                return List.of("DROP TABLE NOTE");
            }
        };
        DdlActions ddl = new DdlActions();

        ActionResult create = ddl.createScript(unit(scripts));
        ActionResult drop = ddl.dropScript(unit(scripts));

        assertEquals(ActionResult.SQL, create.contentType());
        assertEquals("CREATE TABLE NOTE (ID BIGINT);\nCREATE INDEX IDX_NOTE_TITLE ON NOTE (TITLE);\n", create.body());
        assertEquals("2 statements", create.summary());
        assertEquals("DROP TABLE NOTE;\n", drop.body());
        assertEquals("1 statement", drop.summary());
    }

    @Test
    void aUnitWithoutScriptsSaysWhy() {
        ActionResult result = new DdlActions().createScript(unit(null));

        assertTrue(result.error());
        assertEquals("PersistenceException: Unsupported unwrap type " + SchemaScripts.class.getName(),
                result.summary());
    }
}
