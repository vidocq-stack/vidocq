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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.ReportQueries;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDQL tab of the Mansart Data panel (JDQL console spec §3): what it offers, what it refuses before anything runs,
 * how it runs a statement — through a recording {@link JdqlRunner}, no database — and what it answers and keeps.
 */
class JdqlActionsTest {

    private static final String SECRET = "hunter2";
    /** The {@code RepositoryRuntime} bean the lookup gives: the runner only receives it. */
    private static final Object RUNTIME = new Object();

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final RecordingRunner runner = new RecordingRunner();

    /** Records each statement, what it ran with and the transaction's events at that moment; answers or throws. */
    final class RecordingRunner implements JdqlRunner {

        final List<String> queries = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        final List<Map<String, Object>> parameters = new ArrayList<>();
        final List<Class<?>> entities = new ArrayList<>();
        final List<Object> runtimes = new ArrayList<>();
        final List<List<String>> eventsDuringRun = new ArrayList<>();
        JdqlResult answer = new JdqlResult.Entities(List.of());
        RuntimeException failure;

        @Override
        public JdqlResult run(String jdql, Map<String, Object> params, EntityModel<?> model, Object runtime,
                              int maxRows) {
            queries.add(jdql);
            limits.add(maxRows);
            parameters.add(params);
            entities.add(model.entityClass());
            runtimes.add(runtime);
            eventsDuringRun.add(List.copyOf(manager.events));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private RepositoryActions build(TransactionRunner transactions) {
        return RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), type -> {
            if (type == RepositoryRuntime.class) {
                return RUNTIME;
            }
            throw new BeanLookup.NoBean();
        }, transactions, RunFixtures::model, method -> true, RepositoryActions.MAX_ACTIONS, runner);
    }

    private RepositoryActions build() {
        return build(new TransactionRunner(new JtaDemarcation(() -> manager)));
    }

    private static PanelAction action(RepositoryActions actions, String id) {
        return actions.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    private static ActionResult run(RepositoryActions actions, String id, String statement, String transaction) {
        Map<String, String> given = new HashMap<>();
        given.put("statement", statement);
        if (transaction != null) {
            given.put("transaction", transaction);
        }
        return action(actions, id).call().apply(given);
    }

    private static ActionResult query(RepositoryActions actions, String statement) {
        return run(actions, JdqlActions.QUERY, statement, null);
    }

    private static void assertRefused(RepositoryActions actions, String id, String statement, String expected) {
        ActionResult result = run(actions, id, statement, null);
        assertTrue(result.error(), statement);
        assertEquals(expected, result.summary(), statement);
        assertNull(result.body(), statement);
    }

    private static Table calls(RepositoryActions actions) {
        RecordedSample sample = new RecordedSample();
        actions.sample(sample);
        return (Table) sample.value("calls");
    }

    private static Gizmo bolt(long id) {
        return RunFixtures.gizmo(id, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));
    }

    @Test
    void twoActionsInAJdqlTabAfterTheRepositories() {
        List<PanelAction> actions = build().actions();

        PanelAction query = actions.get(actions.size() - 2);
        PanelAction write = actions.getLast();
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE), List.of(query.id(), write.id()));
        assertEquals("m.report-queries.gizmo-count", actions.get(actions.size() - 3).id(), "after the repositories");
        assertEquals("JDQL", query.group());
        assertEquals("JDQL", write.group());
        assertEquals("Query", query.label());
        assertEquals("Update / Delete", write.label());
        assertNull(query.confirmation(), "a query runs on the first click");
        assertEquals("Runs this JDQL statement against the database.", write.confirmation());
        assertEquals(List.of("statement"), query.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals(List.of("statement", "transaction"),
                write.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals(List.of("rollback", "commit"), write.arguments().get(1).allowedValues());
        String schema = query.arguments().getFirst().schema();
        assertEquals(schema, write.arguments().getFirst().schema());
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/x-query\",\"x-language\":\"jdql\",\"description\":\"FROM Product "
                + "WHERE price > :min ORDER BY name\"}"), "the query editor, with the panel's jdql: " + schema);
        assertTrue(schema.contains("\"params\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"x-parameters-of\":\"query\",\"description\":"), "the JSON editor of its parameters: " + schema);
        assertTrue(schema.endsWith("\"required\":[\"query\"]}"), schema);
        assertTrue(query.description().endsWith("Entities: Gizmo, Part"), query.description());
    }

    @Test
    void aQueryRunsOnTheEntityItNamesWithItsParametersInATransactionRolledBack() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Entities(List.of(bolt(1), bolt(2)));

        ActionResult result = query(actions,
                "{\"query\":\" FROM Gizmo WHERE stock > :min \",\"params\":\"{\\\"min\\\": 2}\"}");

        assertFalse(result.error(), result.summary());
        assertTrue(result.summary().matches("2 rows in \\d+ ms"), result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertTrue(result.body().startsWith("[{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\""),
                result.body());
        assertEquals(List.of("FROM Gizmo WHERE stock > :min"), runner.queries, "the statement, stripped");
        assertEquals(List.of(Map.of("min", new BigDecimal("2"))), runner.parameters);
        assertEquals(List.of(Gizmo.class), runner.entities);
        assertSame(RUNTIME, runner.runtimes.getFirst());
        // a query is read-only whatever it holds: should the parser ever take a write for a query, nothing is kept
        assertEquals(List.of("begin", "rollback"), manager.events, "a query runs in a transaction rolled back");
        assertFalse(result.summary().contains("rolled back"), "a query's summary says nothing of it");
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"query\":\"FROM Gizmo WHERE stock > :min\","
                + "\"params\":{\"min\":2},\"transaction\":\"read-only\"}", result.details());
    }

    @Test
    void paramsMayAlsoBeAJsonObjectOrNothing() {
        RepositoryActions actions = build();

        assertFalse(query(actions, "{\"query\":\"FROM Gizmo WHERE name = :name\",\"params\":{\"name\":\"bolt\"}}")
                .error());
        assertFalse(query(actions, "{\"query\":\"FROM Gizmo\"}").error());
        assertFalse(query(actions, "{\"query\":\"FROM Gizmo\",\"params\":\"  \"}").error());

        assertEquals(List.of(Map.of("name", "bolt"), Map.of(), Map.of()), runner.parameters);
    }

    @Test
    void aListParameterIsPassedAsAList() {
        query(build(), "{\"query\":\"FROM Gizmo WHERE level IN :levels\",\"params\":{\"levels\":[\"LOW\",\"HIGH\"]}}");

        assertEquals(List.of(Map.of("levels", List.of("LOW", "HIGH"))), runner.parameters);
    }

    @Test
    void eachResultKindHasItsSummary() {
        RepositoryActions actions = build();

        runner.answer = new JdqlResult.Rows(List.of("name", "stock"), List.<Object[]>of(new Object[] {"bolt", 3}));
        ActionResult rows = query(actions, "{\"query\":\"SELECT name, stock FROM Gizmo\"}");
        assertTrue(rows.summary().matches("1 row in \\d+ ms"), rows.summary());
        assertEquals("[{\"name\":\"bolt\",\"stock\":3}]", rows.body());

        runner.answer = new JdqlResult.Count(42);
        ActionResult count = query(actions, "{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}");
        assertEquals("42", count.summary());
        assertEquals("42", count.body());

        runner.answer = new JdqlResult.Value(new BigDecimal("4.00"));
        ActionResult max = query(actions, "{\"query\":\"SELECT MAX(price) FROM Gizmo\"}");
        assertEquals("4.00", max.summary());
        assertEquals("4.00", max.body());

        runner.answer = new JdqlResult.Value(null);
        assertEquals("null", query(actions, "{\"query\":\"SELECT MAX(price) FROM Gizmo\"}").summary());

        runner.answer = new JdqlResult.Entities(List.of());
        String none = query(actions, "{\"query\":\"FROM Gizmo\"}").summary();
        assertTrue(none.matches("no row in \\d+ ms"), none);
    }

    /** #157: a query reads one row more than it shows, never a whole table. */
    @Test
    void aQueryReadsAtMostOneRowMoreThanItShows() {
        RepositoryActions actions = build();

        query(actions, "{\"query\":\"FROM Gizmo\"}");

        assertEquals(List.of(ResultJson.MAX_ROWS + 1), runner.limits);
    }

    @Test
    void aQueryOfManyRowsShowsTheFirstHundred() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Entities(LongStream.rangeClosed(1, 150).mapToObj(JdqlActionsTest::bolt)
                .toList());

        ActionResult result = query(actions, "{\"query\":\"FROM Gizmo\"}");

        assertTrue(result.summary().matches("first 100 rows in \\d+ ms"), result.summary());
        assertEquals(100, ((List<?>) Json.parse(result.body())).size());
    }

    @Test
    void aWriteRunsInATransactionRolledBackByDefault() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Count(4);

        ActionResult result = run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}", null);

        assertEquals("4 rows · rolled back", result.summary());
        assertEquals("4", result.body());
        assertEquals(List.of(List.of("begin")), runner.eventsDuringRun, "run inside the transaction");
        assertEquals(List.of("begin", "rollback"), manager.events);
        assertTrue(result.details().endsWith("\"transaction\":\"rollback\"}"), result.details());
    }

    @Test
    void aWriteCommitsWhenAsked() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Count(1);

        ActionResult result = run(actions, JdqlActions.WRITE, "{\"query\":\"DELETE FROM Gizmo WHERE stock = 0\"}",
                "commit");

        assertEquals("1 row · committed", result.summary());
        assertEquals(List.of("begin", "commit"), manager.events);
        runner.answer = new JdqlResult.Count(0);
        assertEquals("no row · rolled back",
                run(actions, JdqlActions.WRITE, "{\"query\":\"DELETE FROM Gizmo\"}", "rollback").summary());
    }

    @Test
    void withoutATransactionManagerAWriteCanOnlyBeCommitted() {
        RepositoryActions actions = build(TransactionRunner.NONE);
        runner.answer = new JdqlResult.Count(2);

        assertEquals(List.of("commit"), action(actions, JdqlActions.WRITE).arguments().get(1).allowedValues());
        assertEquals("2 rows · committed",
                run(actions, JdqlActions.WRITE, "{\"query\":\"DELETE FROM Gizmo\"}", null).summary());
    }

    @Test
    void aFailingStatementRollsBackAndShowsItsClassAndItsMaskedMessage() {
        RepositoryActions actions = build();
        runner.failure = new MansartDataException("connection to jdbc:h2:tcp://sa:" + SECRET + "@db/x refused");

        ActionResult result = run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}", "commit");

        String expected = "io.vidocq.mansart.data.core.MansartDataException: "
                + "connection to jdbc:h2:tcp://***:***@db/x refused";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events, "rolled back although commit was asked");
        Table calls = calls(actions);
        assertEquals("error: " + expected, calls.rows().getFirst().get(2));
        assertFalse(calls.toString().contains(SECRET));
    }

    @Test
    void whatIsRefusedRunsNothing() {
        RepositoryActions actions = build();

        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}",
                "an UPDATE or DELETE: use Update / Delete");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"delete from Gizmo\"}",
                "an UPDATE or DELETE: use Update / Delete");
        assertRefused(actions, JdqlActions.WRITE, "{\"query\":\"FROM Gizmo\"}", "not an UPDATE or DELETE: use Query");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Nope\"}",
                "unknown entity Nope; entities: Gizmo, Part");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"WHERE stock > 1\"}",
                "no entity: name it, FROM <Entity>, UPDATE <Entity> or DELETE FROM <Entity>");
        assertRefused(actions, JdqlActions.QUERY, "{}", "query: missing");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"  \"}", "query: missing");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":3}", "query: not a string");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"limit\":3}", "limit: unknown argument");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"params\":\"[1]\"}",
                "params: not a JSON object");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"params\":\"{\"}",
                "params: not valid JSON at character 2");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"params\":{\"x\":{\"a\":1}}}",
                "params.x: not a value or a list of values");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\"",
                "statement: not valid JSON at character 22");

        assertEquals(List.of(), runner.queries, "nothing run");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void withoutARepositoryRuntimeBeanTheCallSaysSo() {
        RepositoryActions actions = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(),
                type -> {
                    throw new BeanLookup.NoBean();
                }, TransactionRunner.NONE, RunFixtures::model, method -> true, RepositoryActions.MAX_ACTIONS, runner);

        ActionResult result = query(actions, "{\"query\":\"FROM Gizmo\"}");

        assertTrue(result.error());
        assertEquals("no RepositoryRuntime bean", result.summary());
        assertEquals(List.of(), runner.queries);
    }

    @Test
    void anAmbiguousSimpleNameIsRefusedAndAFullNameRuns() {
        List<MansartDataCatalogue.Entity> twins = List.of(
                new MansartDataCatalogue.Entity("x.Gizmo", "x.Gizmo", "gizmos", List.of(), null),
                new MansartDataCatalogue.Entity("y.Gizmo", "y.Gizmo", "gizmos", List.of(), null));
        List<String> loaded = new ArrayList<>();
        JdqlActions tab = new JdqlActions(twins, className -> {
            loaded.add(className);
            return Gizmo.class;
        }, RunFixtures::model, RunFixtures.entities(), type -> RUNTIME, TransactionRunner.NONE, runner,
                new CallHistory(), JdqlActions.GROUP);

        ActionResult ambiguous = tab.call(false, Map.of("statement", "{\"query\":\"FROM Gizmo\"}"));
        ActionResult full = tab.call(false, Map.of("statement", "{\"query\":\"FROM y.Gizmo\"}"));

        assertEquals("entity Gizmo is ambiguous: x.Gizmo, y.Gizmo; use its full name", ambiguous.summary());
        assertFalse(full.error(), full.summary());
        assertEquals(List.of("y.Gizmo"), loaded);
        assertEquals(List.of("FROM y.Gizmo"), runner.queries);
    }

    @Test
    void eachCallIsKeptWithItsReplayInTheJdqlTab() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Count(3);
        query(actions, "{\"query\": \"FROM Gizmo WHERE stock > :min\", \"params\": \"{\\\"min\\\":2}\"}");
        run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}", "rollback");

        Table calls = calls(actions);

        assertEquals(CallHistory.COLUMNS, calls.columns());
        List<String> write = calls.rows().get(0);
        List<String> read = calls.rows().get(1);
        assertEquals("Update / Delete", write.get(1));
        assertEquals("3 rows · rolled back", write.get(2));
        assertEquals("jdql.write {\"statement\":{\"query\":\"UPDATE Gizmo SET stock = 0\"},"
                + "\"transaction\":\"rollback\"}", write.get(5));
        assertEquals("Query", read.get(1));
        assertEquals("3", read.get(2));
        assertEquals("{\"query\":\"FROM Gizmo WHERE stock > :min\",\"params\":\"{\\\"min\\\":2}\"}", read.get(4),
                "the JSON sent, compact");
        assertEquals("jdql.query {\"statement\":{\"query\":\"FROM Gizmo WHERE stock > :min\","
                + "\"params\":\"{\\\"min\\\":2}\"}}", read.get(5));
    }

    @Test
    void aCatalogueWithoutEntityHasNoJdqlTab() {
        MansartDataCatalogue.Repository reports = new MansartDataCatalogue.Repository("ReportQueries",
                ReportQueries.class.getName(), null, null, null, List.of(), 1, "");
        MansartDataCatalogue noEntity = new MansartDataCatalogue(List.of(), List.of(reports), 0, 1, 1);

        List<String> ids = RepositoryActions.build(List.of(ReportQueries.class), noEntity, type -> RUNTIME,
                        TransactionRunner.NONE, RunFixtures::model, method -> true, RepositoryActions.MAX_ACTIONS,
                        runner)
                .actions().stream().map(PanelAction::id).toList();

        assertEquals(List.of("m.report-queries.gizmo-count"), ids);
    }

    @Test
    void theTabTakesItsTwoActionsFromThePanelsLimit() {
        List<String> ids = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(),
                        type -> RUNTIME, TransactionRunner.NONE, RunFixtures::model, method -> true, 5, runner)
                .actions().stream().map(PanelAction::id).toList();

        assertEquals(5, ids.size());
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE), ids.subList(3, 5));
    }

    @Test
    void anEntityClassIsLoadedThroughTheRepositoriesLoader() {
        assertSame(Gizmo.class, RepositoryActions.load(Gizmo.class.getName(), RunFixtures.REPOSITORIES));
    }
}
