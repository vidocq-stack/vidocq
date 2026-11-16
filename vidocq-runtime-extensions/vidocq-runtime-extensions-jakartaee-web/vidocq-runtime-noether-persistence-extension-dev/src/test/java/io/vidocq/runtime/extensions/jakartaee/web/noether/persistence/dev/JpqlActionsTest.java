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

import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JPQL tab: a query shows its rows, a write runs in a transaction, an export is CSV. */
class JpqlActionsTest {

    private final List<String> calls = new ArrayList<>();
    private final Map<String, Object> bound = new HashMap<>();
    private final JpqlActions jpql = new JpqlActions(new UnitWork(() -> null));

    /** A RESOURCE_LOCAL unit of {@link ModelGraphTest#UNIT} whose queries answer {@code rows} or {@code updated}. */
    private Unit unit(List<?> rows, int updated, Function<Object[], Object> createQuery) {
        Parameter<?> since = Stubs.of(Parameter.class, calls, Map.of("getParameterType", args -> LocalDate.class));
        Query query = Stubs.of(Query.class, calls, Map.of(
                "setMaxResults", args -> Stubs.SELF,
                "getParameter", args -> since,
                "setParameter", args -> {
                    bound.put((String) args[0], args[1]);
                    return Stubs.SELF;
                },
                "getResultList", args -> rows,
                "executeUpdate", args -> updated));
        boolean[] active = {false};
        Function<Object[], Object> ends = args -> {
            active[0] = false;
            return null;
        };
        EntityTransaction transaction = Stubs.of(EntityTransaction.class, calls, Map.of("begin", args -> {
            active[0] = true;
            return null;
        }, "commit", ends, "rollback", ends, "isActive", args -> active[0]));
        EntityManager em = Stubs.of(EntityManager.class, calls, Map.of(
                "getTransaction", args -> transaction,
                "createQuery", createQuery == null ? args -> query : createQuery,
                "close", Stubs.NOTHING));
        return new Unit("notes", false, null, "none", new StubFactory("notes", UNIT, null, () -> em));
    }

    private static Map<String, String> statement(String query) {
        return Map.of(JpqlActions.STATEMENT, Json.write(Map.of("query", query)));
    }

    @Test
    void theTabHasAQueryAWriteThatAsksFirstAndAnExport() {
        List<PanelAction> actions = jpql.actions(new PanelAction.Argument("unit", "Persistence unit",
                List.of("notes"), null, null), call -> given -> ActionResult.of("called"), "SELECT e FROM Note e");

        assertEquals(List.of("jpql.query", "jpql.write", "jpql.export"), actions.stream().map(PanelAction::id)
                .toList());
        assertTrue(actions.stream().allMatch(a -> a.group().equals("JPQL")));
        assertNull(actions.get(0).confirmation());
        assertNotNull(actions.get(1).confirmation());
        assertEquals(List.of("rollback", "commit"), actions.get(1).arguments().get(2).allowedValues());
        assertTrue(actions.get(0).arguments().get(1).schema().contains("\"x-language\":\"jpql\""));
        assertTrue(actions.get(0).arguments().get(1).schema().contains("SELECT e FROM Note e"));
    }

    @Test
    void aQueryShowsItsRowsAndIsRolledBack() {
        Unit unit = unit(ResultRowsTest.results(new Object[] {"first", 1L}, new Object[] {"second", 2L}), 0, null);

        ActionResult result = jpql.query(unit, statement("SELECT n.title, n.id FROM Note n"));

        assertFalse(result.error(), result.summary());
        assertEquals(ActionResult.ROWS, result.contentType());
        assertTrue(result.summary().matches("2 rows in \\d+ ms"), result.summary());
        assertTrue(result.body().contains("\"n.title\""), result.body());
        assertTrue(calls.contains("rollback"));
        assertFalse(calls.contains("commit"));
        assertTrue(calls.contains("setMaxResults"));
    }

    @Test
    void aParameterTakesTheTypeTheQueryDeclares() {
        Unit unit = unit(List.of(), 0, null);

        jpql.query(unit, Map.of(JpqlActions.STATEMENT, "{\"query\": \"SELECT n FROM Note n WHERE n.day >= :since\","
                + " \"params\": {\"since\": \"2026-11-16\"}}"));

        assertEquals(LocalDate.of(2026, 11, 16), bound.get("since"));
    }

    @Test
    void eachActionRefusesTheOtherKindOfStatementBeforeAnythingRuns() {
        Unit unit = unit(List.of(), 0, null);

        ActionResult query = jpql.query(unit, statement("DELETE FROM Note n"));
        ActionResult write = jpql.write(unit, statement("SELECT n FROM Note n"));
        ActionResult export = jpql.export(unit, statement("UPDATE Note n SET n.title = 'x'"));

        assertEquals("an UPDATE or a DELETE: use Update / Delete", query.summary());
        assertEquals("not an UPDATE or a DELETE: use Query", write.summary());
        assertEquals("an UPDATE or a DELETE: use Update / Delete", export.summary());
        assertTrue(query.error() && write.error() && export.error());
        assertEquals(List.of(), calls);
        assertEquals("statement: not a JSON object", jpql.query(unit, Map.of(JpqlActions.STATEMENT, "3")).summary());
    }

    @Test
    void aWriteIsRolledBackUnlessCommitIsAsked() {
        ActionResult rolledBack = jpql.write(unit(List.of(), 3, null), statement("UPDATE Note n SET n.title = 'x'"));
        assertEquals("3 rows updated · rolled back", rolledBack.summary());
        assertTrue(calls.contains("rollback"));

        calls.clear();
        Map<String, String> given = new HashMap<>(statement("DELETE FROM Note n WHERE n.id = 1"));
        given.put(JpqlActions.TRANSACTION, "commit");
        ActionResult committed = jpql.write(unit(List.of(), 1, null), given);
        assertEquals("1 row deleted · committed", committed.summary());
        assertTrue(calls.contains("commit"));
        assertFalse(calls.contains("rollback"));
    }

    @Test
    void aFailureIsTheExceptionOnOneLineAndInFull() {
        Unit unit = unit(List.of(), 0, args -> {
            throw new IllegalArgumentException("Unexpected token SELEC\n  at line 1, password=secret");
        });

        ActionResult result = jpql.query(unit, statement("SELEC n FROM Note n"));

        assertTrue(result.error());
        assertEquals("IllegalArgumentException: Unexpected token SELEC at line 1, password=***", result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertTrue(calls.contains("rollback") && calls.getLast().equals("close"), calls.toString());
    }

    @Test
    void anExportIsTheRowsAsCsv() {
        Unit unit = unit(ResultRowsTest.results(new Object[] {"first", 1L}, new Object[] {"a, \"b\"", 2L}), 0, null);

        ActionResult result = jpql.export(unit, statement("SELECT n.title, n.id FROM Note n"));

        assertEquals(ActionResult.CSV, result.contentType());
        assertEquals("n.title,n.id\nfirst,1\n\"a, \"\"b\"\"\",2\n", result.body());
        assertEquals("2 rows", result.summary());
    }
}
