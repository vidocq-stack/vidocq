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
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Slot;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV actions of the JDQL tab (CSV spec §3-§6): what they offer, what they refuse before anything runs, the CSV an
 * export writes and what an import saves — through a recording {@link JdqlRunner} and {@link EntitySaver}, no
 * database — and the calls they keep.
 */
class CsvActionsTest {

    private static final String SECRET = "hunter2";
    /** The {@code RepositoryRuntime} bean the lookup gives: the runner and the saver only receive it. */
    private static final Object RUNTIME = new Object();
    private static final BeanLookup BEANS = type -> {
        if (type == RepositoryRuntime.class) {
            return RUNTIME;
        }
        throw new BeanLookup.NoBean();
    };

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final RecordingRunner runner = new RecordingRunner();
    private final RecordingSaver saver = new RecordingSaver();
    private final CallHistory history = new CallHistory();
    private final EntityJson json = RunFixtures.entities();

    /** Records each statement, its parameters and the transaction's events at that moment; answers or throws. */
    final class RecordingRunner implements JdqlRunner {

        final List<String> queries = new ArrayList<>();
        final List<Map<String, Object>> parameters = new ArrayList<>();
        final List<List<String>> eventsDuringRun = new ArrayList<>();
        JdqlResult answer = new JdqlResult.Entities(List.of());
        RuntimeException failure;

        @Override
        public JdqlResult run(String jdql, Map<String, Object> params, EntityModel<?> model, Object runtime) {
            queries.add(jdql);
            parameters.add(params);
            eventsDuringRun.add(List.copyOf(manager.events));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /** Records each entity saved and the transaction's events at that moment; the save number {@code failAt} throws. */
    final class RecordingSaver implements EntitySaver {

        final List<Object> saved = new ArrayList<>();
        final List<Class<?>> models = new ArrayList<>();
        final List<List<String>> eventsDuringSave = new ArrayList<>();
        /** The save, counted from 1, that throws; 0 for none. */
        int failAt;

        @Override
        public Object save(EntityModel<?> model, Object entity, Object runtime) {
            assertSame(RUNTIME, runtime);
            models.add(model.entityClass());
            eventsDuringSave.add(List.copyOf(manager.events));
            if (saved.size() + 1 == failAt) {
                throw new IllegalStateException("duplicate key on jdbc:h2:tcp://sa:" + SECRET + "@db/x");
            }
            saved.add(entity);
            return entity;
        }
    }

    /** The CSV actions over the catalogue's Gizmo and Part, and Slot, whose Year the console cannot convert. */
    private CsvActions tab(TransactionRunner transactions, BeanLookup beans) {
        List<MansartDataCatalogue.Entity> entities = new ArrayList<>(RunFixtures.catalogue().entities());
        entities.add(new MansartDataCatalogue.Entity("Slot", Slot.class.getName(), "slots", List.of(), null));
        JdqlActions jdql = new JdqlActions(entities,
                className -> RepositoryActions.load(className, RunFixtures.REPOSITORIES), RunFixtures::model, json,
                beans, transactions, runner, history, JdqlActions.GROUP);
        return new CsvActions(jdql, json, transactions, runner, saver, history, JdqlActions.GROUP);
    }

    private CsvActions tab() {
        return tab(new TransactionRunner(new JtaDemarcation(() -> manager)), BEANS);
    }

    private static ActionResult export(CsvActions tab, String statement) {
        return tab.exportCsv(Map.of(CsvActions.STATEMENT, statement));
    }

    private static void assertRefused(ActionResult result, String expected) {
        assertTrue(result.error(), result.summary());
        assertEquals(expected, result.summary());
        assertNull(result.body(), expected);
    }

    private static Gizmo bolt(long id) {
        return RunFixtures.gizmo(id, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void theExportActionIsAQueryOfTheJdqlTab() {
        PanelAction export = tab().actions().getFirst();

        assertEquals(CsvActions.EXPORT, export.id());
        assertEquals("Export CSV", export.label());
        assertEquals("JDQL", export.group());
        assertNull(export.confirmation(), "an export writes nothing: no confirmation");
        assertEquals(List.of("statement"), export.arguments().stream().map(PanelAction.Argument::name).toList());
        String schema = export.arguments().getFirst().schema();
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
        assertTrue(schema.contains("\"separator\":{\"type\":\"string\",\"enum\":[\",\",\";\"],\"default\":\",\""),
                schema);
        assertTrue(schema.endsWith("\"required\":[\"query\"]}"), schema);
        assertTrue(export.description().endsWith("Entities: Gizmo, Part, Slot"), export.description());
    }

    @Test
    void anExportOfEntitiesIsEveryAttributeInModelOrderOneRowEach() {
        CsvActions tab = tab();
        runner.answer = new JdqlResult.Entities(List.of(bolt(1),
                RunFixtures.gizmo(2L, "nut, \"big\"", 0, Level.HIGH, null, new BigDecimal("1E+1"))));

        ActionResult result = export(tab, "{\"query\":\" FROM Gizmo ORDER BY id \"}");

        assertFalse(result.error(), result.summary());
        assertEquals(ActionResult.CSV, result.contentType());
        assertEquals("id,name,stock,level,due,price\r\n1,bolt,3,LOW,2026-10-01,2.50\r\n"
                + "2,\"nut, \"\"big\"\"\",0,HIGH,,10\r\n", result.body());
        assertTrue(result.summary().matches("2 rows · " + utf8(result.body()) + " B in \\d+ ms"), result.summary());
        assertEquals(List.of("FROM Gizmo ORDER BY id"), runner.queries, "the statement, stripped");
        assertEquals(List.of(List.of("begin")), runner.eventsDuringRun, "run in a transaction");
        assertEquals(List.of("begin", "rollback"), manager.events, "always rolled back: an export writes nothing");
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"query\":\"FROM Gizmo ORDER BY id\","
                + "\"params\":{},\"separator\":\",\"}", result.details());
        assertEquals(List.of(), saver.saved);
    }

    @Test
    void aProjectionIsItsColumnsAndASemicolonKeepsDecimalPointsUnquoted() {
        runner.answer = new JdqlResult.Rows(List.of("name", "price"), List.<Object[]>of(
                new Object[] {"a;b", new BigDecimal("2.50")}, new Object[] {null, 3.5}, new Object[] {"c,d", null}));

        ActionResult result = export(tab(), "{\"query\":\"SELECT name, price FROM Gizmo WHERE stock > :min\","
                + "\"params\":{\"min\":2},\"separator\":\";\"}");

        assertEquals("name;price\r\n\"a;b\";2.50\r\n;3.5\r\nc,d;\r\n", result.body());
        assertTrue(result.summary().startsWith("3 rows · "), result.summary());
        assertEquals(List.of(Map.of("min", new BigDecimal("2"))), runner.parameters);
        assertTrue(result.details().endsWith("\"params\":{\"min\":2},\"separator\":\";\"}"), result.details());
    }

    @Test
    void aCountAndAValueAreOneColumnOfOneRowNullAnEmptyField() {
        CsvActions tab = tab();

        runner.answer = new JdqlResult.Count(42);
        ActionResult count = export(tab, "{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}");
        assertEquals("count\r\n42\r\n", count.body());
        assertTrue(count.summary().matches("1 row · 11 B in \\d+ ms"), count.summary());

        runner.answer = new JdqlResult.Value(null);
        assertEquals("value\r\n\r\n", export(tab, "{\"query\":\"SELECT MAX(price) FROM Gizmo\"}").body(),
                "null: an empty field");
        runner.answer = new JdqlResult.Value("");
        assertEquals("value\r\n\"\"\r\n", export(tab, "{\"query\":\"SELECT MAX(name) FROM Gizmo\"}").body(),
                "the empty text: \"\"");
        runner.answer = new JdqlResult.Value(LocalDate.of(2026, 10, 1));
        assertEquals("value\r\n2026-10-01\r\n", export(tab, "{\"query\":\"SELECT MAX(due) FROM Gizmo\"}").body());

        runner.answer = new JdqlResult.Entities(List.of());
        ActionResult none = export(tab, "{\"query\":\"FROM Gizmo WHERE stock > 99\"}");
        assertEquals("id,name,stock,level,due,price\r\n", none.body(), "no row: the header only");
        assertTrue(none.summary().matches("no row · 31 B in \\d+ ms"), none.summary());
    }

    @Test
    void aReferenceIsItsIdAJoinedAttributeIsLeftOutAnUnsupportedTypeIsItsText() {
        CsvActions tab = tab();

        runner.answer = new JdqlResult.Entities(List.of(RunFixtures.part(5L, bolt(1), "left")));
        assertEquals("id,gizmo,label\r\n5,1,left\r\n", export(tab, "{\"query\":\"FROM Part\"}").body());

        runner.answer = new JdqlResult.Entities(List.of(RunFixtures.slot(1L, Year.of(2026), "x")));
        assertEquals("id,year,label\r\n1,2026,x\r\n", export(tab, "{\"query\":\"FROM Slot\"}").body());

        runner.answer = new JdqlResult.Rows(List.of("gizmo"), List.<Object[]>of(new Object[] {bolt(7)}));
        assertEquals("gizmo\r\n7\r\n", export(tab, "{\"query\":\"SELECT gizmo FROM Part\"}").body(),
                "an entity in a projection: its id");
    }

    @Test
    void theLimitIs256KiBOfUtf8NotOfCharacters() {
        CsvActions tab = tab();
        runner.answer = new JdqlResult.Entities(LongStream.rangeClosed(1, 1400)
                .mapToObj(id -> RunFixtures.gizmo(id, "e".repeat(100), 3, Level.LOW, null, null)).toList());

        ActionResult ascii = export(tab, "{\"query\":\"FROM Gizmo\"}");

        assertFalse(ascii.error(), ascii.summary());
        assertTrue(ascii.summary().startsWith("1400 rows · "), ascii.summary());
        assertEquals(utf8(ascii.body()), ascii.body().length(), "ASCII: as many bytes as characters");

        runner.answer = new JdqlResult.Entities(LongStream.rangeClosed(1, 1400)
                .mapToObj(id -> RunFixtures.gizmo(id, "é".repeat(100), 3, Level.LOW, null, null)).toList());

        ActionResult accented = export(tab, "{\"query\":\"FROM Gizmo\"}");

        assertRefused(accented, "larger than 256 KiB: narrow the query");
        assertEquals(List.of("begin", "rollback", "begin", "rollback"), manager.events);
    }

    @Test
    void whatIsRefusedRunsNothing() {
        CsvActions tab = tab();

        assertRefused(export(tab, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}"), "an UPDATE or DELETE: export a query");
        assertRefused(export(tab, "{\"query\":\"delete from Gizmo\"}"), "an UPDATE or DELETE: export a query");
        assertRefused(export(tab, "{\"query\":\"FROM Gizmo\",\"separator\":\"|\"}"), "separator: \",\" or \";\"");
        assertRefused(export(tab, "{\"query\":\"FROM Gizmo\",\"limit\":3}"), "limit: unknown argument");
        assertRefused(export(tab, "{}"), "query: missing");
        assertRefused(export(tab, "{\"query\":3}"), "query: not a string");
        assertRefused(export(tab, "{\"query\":\"FROM Nope\"}"), "unknown entity Nope; entities: Gizmo, Part, Slot");
        assertRefused(export(tab, "[1]"), "statement: not a JSON object");
        assertRefused(export(tab, "{\"query\":\"FROM Gizmo\""), "statement: not valid JSON at character 22");
        assertRefused(export(tab(TransactionRunner.NONE, type -> {
            throw new BeanLookup.NoBean();
        }), "{\"query\":\"FROM Gizmo\"}"), "no RepositoryRuntime bean");

        assertEquals(List.of(), runner.queries, "nothing run");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void aFailingQueryShowsItsClassAndItsMaskedMessage() {
        runner.failure = new MansartDataException("connection to jdbc:h2:tcp://sa:" + SECRET + "@db/x refused");

        ActionResult result = export(tab(), "{\"query\":\"FROM Gizmo\"}");

        String expected = "io.vidocq.mansart.data.core.MansartDataException: "
                + "connection to jdbc:h2:tcp://***:***@db/x refused";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void aSizeIsInBytesThenKiB() {
        assertEquals("90 B", CsvActions.size(90));
        assertEquals("1023 B", CsvActions.size(1023));
        assertEquals("1.0 KiB", CsvActions.size(1024));
        assertEquals("3.1 KiB", CsvActions.size(3174));
        assertEquals("256.0 KiB", CsvActions.size(256 * 1024));
    }

    private static ActionResult load(CsvActions tab, String entity, String csv, String separator, String transaction) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("entity", entity);
        file.put("csv", csv);
        if (separator != null) {
            file.put("separator", separator);
        }
        Map<String, String> given = new HashMap<>();
        given.put(CsvActions.FILE, Json.write(file));
        if (transaction != null) {
            given.put("transaction", transaction);
        }
        return tab.importCsv(given);
    }

    private String saved(int i) {
        return Json.write(json.toJson(saver.saved.get(i)));
    }

    private void assertNothingSaved() {
        assertEquals(List.of(), saver.saved, "nothing saved");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void theImportActionAsksFirstAndTakesAFileAndATransaction() {
        List<PanelAction> actions = tab().actions();

        assertEquals(List.of(CsvActions.EXPORT, CsvActions.IMPORT), actions.stream().map(PanelAction::id).toList());
        PanelAction load = actions.get(1);
        assertEquals("Import CSV", load.label());
        assertEquals("JDQL", load.group());
        assertEquals("Saves these CSV rows against the database.", load.confirmation());
        assertEquals(List.of("file", "transaction"), load.arguments().stream().map(PanelAction.Argument::name)
                .toList());
        assertEquals(List.of("rollback", "commit"), load.arguments().get(1).allowedValues());
        String schema = load.arguments().getFirst().schema();
        assertTrue(schema.contains("\"entity\":{\"type\":\"string\",\"enum\":[\"Gizmo\",\"Part\",\"Slot\"]"), schema);
        assertTrue(schema.contains("\"csv\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/csv\""), schema);
        assertTrue(schema.endsWith("\"required\":[\"entity\",\"csv\"]}"), schema);
    }

    @Test
    void eachRowIsAnEntitySavedInOrderInOneTransactionRolledBackByDefault() {
        ActionResult result = load(tab(), "Gizmo",
                "id,name,stock,level,due,price\r\n,bolt,3,LOW,2026-10-01,2.50\n7,\"\",0,HIGH,,\r\n", null, null);

        assertFalse(result.error(), result.summary());
        assertEquals("2 rows saved · rolled back", result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"saved\":2,\"transaction\":\"rollback\"}",
                result.body());
        assertEquals("{\"id\":null,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":\"2026-10-01\","
                + "\"price\":2.50}", saved(0), "an empty id: an insert, its id generated");
        assertEquals("{\"id\":7,\"name\":\"\",\"stock\":0,\"level\":\"HIGH\",\"due\":null,\"price\":null}", saved(1),
                "an id kept for an upsert; \"\" the empty text, an empty field null");
        assertEquals(List.of(Gizmo.class, Gizmo.class), saver.models);
        assertEquals(List.of(List.of("begin"), List.of("begin")), saver.eventsDuringSave, "saved inside one transaction");
        assertEquals(List.of("begin", "rollback"), manager.events);
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"rows\":2,\"separator\":\",\","
                + "\"transaction\":\"rollback\"}", result.details());
    }

    @Test
    void anImportCommitsWhenAsked() {
        ActionResult result = load(tab(), "Gizmo", "name\r\nbolt\r\n", null, "commit");

        assertEquals("1 row saved · committed", result.summary());
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void withoutATransactionManagerAnImportCanOnlyBeCommitted() {
        CsvActions tab = tab(TransactionRunner.NONE, BEANS);

        assertEquals(List.of("commit"), tab.actions().get(1).arguments().get(1).allowedValues());
        assertEquals("2 rows saved · committed", load(tab, "Gizmo", "name\r\nbolt\r\nnut\r\n", null, null).summary());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void withoutATransactionManagerTheRowsBeforeAFailingOneStayAndTheLineSaysSo() {
        saver.failAt = 2;

        ActionResult result = load(tab(TransactionRunner.NONE, BEANS), "Gizmo", "name\r\nbolt\r\nnut\r\nscrew\r\n",
                null, "commit");

        assertTrue(result.error());
        assertEquals("line 3: java.lang.IllegalStateException: duplicate key on jdbc:h2:tcp://***:***@db/x"
                + " · 1 row before it stays committed", result.summary());
        assertEquals(1, saver.saved.size());
    }

    @Test
    void aSaveThatThrowsRollsEverythingBackNamingItsLine() {
        saver.failAt = 2;
        CsvActions tab = tab();

        ActionResult result = load(tab, "Gizmo", "name\r\nbolt\r\nnut\r\nscrew\r\n", null, "commit");

        String expected = "line 3: java.lang.IllegalStateException: duplicate key on jdbc:h2:tcp://***:***@db/x";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events, "rolled back although commit was asked");
        CallHistory.Call call = history.calls(JdqlActions.GROUP).getFirst();
        assertEquals("error: " + expected, call.outcome());
        assertFalse(call.toString().contains(SECRET));
    }

    @Test
    void theHeaderNamesAttributesThatCanBeSetOnceEach() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Gizmo", "name,colour\r\nbolt,red\r\n", null, null),
                "header: unknown attribute colour; attributes: id, name, stock, level, due, price");
        assertRefused(load(tab, "Part", "label,name\r\nleft,bolt\r\n", null, null),
                "header: unknown attribute name; attributes: id, gizmo, label");
        assertRefused(load(tab, "Slot", "id,year,label\r\n1,2026,x\r\n", null, null),
                "header: year cannot be imported; attributes: id, label");
        assertRefused(load(tab, "Gizmo", "name,stock,name\r\nbolt,3,nut\r\n", null, null), "header: name twice");
        assertRefused(load(tab, "Gizmo", "name,,stock\r\nbolt,,3\r\n", null, null), "header: column 2 has no name");
        assertNothingSaved();
    }

    @Test
    void aConversionErrorNamesTheLineOfTheTextAndTheAttribute() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Part", "id,gizmo,label\r\n1,1,\"two\r\nlines\"\r\n2,x,y\r\n", null, null),
                "line 4, gizmo: not an integer");
        assertRefused(load(tab, "Gizmo", "name;price\r\nbolt;2,50\r\n", ";", null), "line 2, price: not a number");
        assertRefused(load(tab, "Gizmo", "name,stock\r\nbolt,\r\n", null, null),
                "line 2, stock: null is not allowed for int");
        assertRefused(load(tab, "Gizmo", "name,level\r\nbolt,URGENT\r\n", null, null),
                "line 2, level: no constant URGENT in Level");
        assertRefused(load(tab, "Gizmo", "name,due\r\nbolt,tomorrow\r\n", null, null),
                "line 2, due: not an ISO date");
        assertRefused(load(tab, "Gizmo", "name,stock\r\nbolt\r\n", null, null), "line 2: 1 field, the header has 2");
        assertRefused(load(tab, "Gizmo", "name,stock\r\nbolt,3,x\r\n", null, null),
                "line 2: 3 fields, the header has 2");
        assertRefused(load(tab, "Gizmo", "name\r\n\"bolt\r\n", null, null), "line 2: unterminated quoted field");
        assertNothingSaved();
    }

    @Test
    void aReferenceIsBuiltFromItsIdAndASemicolonFileKeepsItsQuotedFields() {
        CsvActions tab = tab();

        assertFalse(load(tab, "Part", "id;gizmo;label\r\n5;1;\"a;b\"\r\n", ";", null).error());
        assertFalse(load(tab, "Gizmo", "\uFEFFname;price\r\n\"c,d\";2.50\r\n", ";", null).error());

        assertEquals("{\"id\":5,\"gizmo\":1,\"label\":\"a;b\"}", saved(0));
        assertEquals("{\"id\":null,\"name\":\"c,d\",\"stock\":0,\"level\":null,\"due\":null,\"price\":2.50}", saved(1),
                "a byte order mark ignored; an attribute without a column keeps the constructor's value");
    }

    @Test
    void atMost5000Rows() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Gizmo", "name\r\n" + "b\r\n".repeat(5001), null, null),
                "more than 5000 rows: split the file");
        assertEquals(List.of(), saver.saved);
        assertEquals("5000 rows saved · rolled back",
                load(tab, "Gizmo", "name\r\n" + "b\r\n".repeat(5000), null, null).summary());
        assertEquals(5000, saver.saved.size());
    }

    @Test
    void whatIsRefusedBeforeReadingSavesNothing() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Gizmo", "name\r\n", null, null), "no row: the file has a header only");
        assertRefused(load(tab, "Gizmo", "  ", null, null), "csv: missing");
        assertRefused(load(tab, "Nope", "name\r\nb\r\n", null, null), "unknown entity Nope; entities: Gizmo, Part, Slot");
        assertRefused(load(tab, "Gizmo", "name\r\nb\r\n", "|", null), "separator: \",\" or \";\"");
        assertRefused(tab.importCsv(Map.of(CsvActions.FILE, "{\"csv\":\"name\\r\\nb\"}")), "entity: missing");
        assertRefused(tab.importCsv(Map.of(CsvActions.FILE, "{\"entity\":\"Gizmo\",\"csv\":\"a\",\"x\":1}")),
                "x: unknown argument");
        assertRefused(tab.importCsv(Map.of(CsvActions.FILE, "{\"query\":\"FROM Gizmo\"")),
                "file: not valid JSON at character 22");
        assertRefused(load(tab(TransactionRunner.NONE, type -> {
            throw new BeanLookup.NoBean();
        }), "Gizmo", "name\r\nb\r\n", null, null), "no RepositoryRuntime bean");
        assertNothingSaved();
    }

    @Test
    void anImportIsKeptWithItsArgumentsCutAndAReplayOnlyWhileItIsShort() {
        CsvActions tab = tab();

        load(tab, "Gizmo", "name\r\nbolt\r\n", null, "commit");
        load(tab, "Gizmo", "name\r\n" + "b\r\n".repeat(2000), null, "rollback");

        List<CallHistory.Call> calls = history.calls(JdqlActions.GROUP);
        assertEquals("Import CSV", calls.get(1).method());
        assertEquals("1 row saved · committed", calls.get(1).outcome());
        assertEquals("jdql.import {\"file\":{\"entity\":\"Gizmo\",\"csv\":\"name\\r\\nbolt\\r\\n\"},"
                + "\"transaction\":\"commit\"}", calls.get(1).replay());
        assertEquals(201, calls.getFirst().arguments().length(), "200 characters and …");
        assertEquals("", calls.getFirst().replay(), "past 4096 characters, no replay");
    }

    @Test
    void theJdqlTabEndsWithTheCsvActionsWhichCountAgainstThePanelsLimit() {
        List<String> ids = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), BEANS,
                        TransactionRunner.NONE, RunFixtures::model, method -> true, 7, runner, saver)
                .actions().stream().map(PanelAction::id).toList();
        List<String> without = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), BEANS,
                        TransactionRunner.NONE, RunFixtures::model, method -> true, 7, runner)
                .actions().stream().map(PanelAction::id).toList();

        assertEquals(7, ids.size());
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE, CsvActions.EXPORT, CsvActions.IMPORT),
                ids.subList(3, 7), "the repositories get what is left");
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE), without.subList(5, 7), "no saver, no CSV action");
    }

    @Test
    void theCsvCallsGoIntoThePanelsCallsTable() {
        RepositoryActions actions = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), BEANS,
                new TransactionRunner(new JtaDemarcation(() -> manager)), RunFixtures::model, method -> true,
                RepositoryActions.MAX_ACTIONS, runner, saver);
        runner.answer = new JdqlResult.Count(3);
        PanelAction export = actions.actions().stream().filter(a -> a.id().equals(CsvActions.EXPORT)).findFirst()
                .orElseThrow();
        PanelAction load = actions.actions().stream().filter(a -> a.id().equals(CsvActions.IMPORT)).findFirst()
                .orElseThrow();

        export.call().apply(Map.of("statement", "{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}"));
        load.call().apply(Map.of("file", "{\"entity\":\"Part\",\"csv\":\"label\\r\\nleft\\r\\n\"}",
                "transaction", "rollback"));

        RecordedSample sample = new RecordedSample();
        actions.sample(sample);
        Table calls = (Table) sample.value("calls");
        assertEquals("Import CSV", calls.rows().get(0).get(1));
        assertEquals("1 row saved · rolled back", calls.rows().get(0).get(2));
        assertEquals("Export CSV", calls.rows().get(1).get(1));
        assertEquals("jdql.export {\"statement\":{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}}",
                calls.rows().get(1).get(5));
        assertEquals(List.of(Part.class), saver.models, "the catalogue's Part, by its name");
    }

    @Test
    void aPrimitiveIdIsNeverGeneratedSoItsColumnIsRequired() {
        List<MansartDataCatalogue.Entity> entities = new ArrayList<>(RunFixtures.catalogue().entities());
        entities.add(new MansartDataCatalogue.Entity("Tally", RunFixtures.Tally.class.getName(), "tallies", List.of(),
                null));
        TransactionRunner transactions = new TransactionRunner(new JtaDemarcation(() -> manager));
        JdqlActions jdql = new JdqlActions(entities,
                className -> RepositoryActions.load(className, RunFixtures.REPOSITORIES), RunFixtures::model, json,
                BEANS, transactions, runner, history, JdqlActions.GROUP);
        CsvActions tab = new CsvActions(jdql, json, transactions, runner, saver, history, JdqlActions.GROUP);

        assertRefused(load(tab, "Tally", "label\r\na\r\nb\r\n", null, "commit"),
                "header: id is a long, which is never generated: give each row its id");
        assertNothingSaved();
        assertEquals("2 rows saved · committed", load(tab, "Tally", "id,label\r\n1,a\r\n2,b\r\n", null, "commit")
                .summary());
    }

    @Test
    void blankLinesAtTheEndOfTheFileAreNoRows() {
        CsvActions tab = tab();

        assertEquals("2 rows saved · rolled back", load(tab, "Gizmo", "name\r\nA\r\nB\r\n\r\n\n", null, null)
                .summary(), "one column: a blank line at the end is no entity of null");
        assertEquals("1 row saved · rolled back", load(tab, "Gizmo", "name,stock\r\nbolt,3\r\n\r\n", null, null)
                .summary(), "several columns: no field-count refusal for it");
        assertRefused(load(tab, "Gizmo", "name\r\n\r\n", null, null), "no row: the file has a header only");
        assertRefused(load(tab, "Gizmo", "name,stock\r\n\r\nbolt,3\r\n", null, null),
                "line 2: 1 field, the header has 2");
    }

    @Test
    void anExportIsKeptInTheTabsHistoryWithItsReplay() {
        CsvActions tab = tab();
        runner.answer = new JdqlResult.Count(3);

        export(tab, "{\"query\": \"SELECT COUNT(this) FROM Gizmo\", \"separator\": \";\"}");

        List<CallHistory.Call> calls = history.calls(JdqlActions.GROUP);
        assertEquals(1, calls.size());
        assertEquals("Export CSV", calls.getFirst().method());
        assertTrue(calls.getFirst().outcome().startsWith("1 row · "), calls.getFirst().outcome());
        assertEquals("{\"query\":\"SELECT COUNT(this) FROM Gizmo\",\"separator\":\";\"}", calls.getFirst().arguments(),
                "the JSON sent, compact");
        assertEquals("jdql.export {\"statement\":{\"query\":\"SELECT COUNT(this) FROM Gizmo\",\"separator\":\";\"}}",
                calls.getFirst().replay());
    }
}
