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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Text;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.GizmoRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run actions of the Mansart Data panel: what they are, and what a call does (spec §2-§8). */
class RepositoryActionsTest {

    private static final String SECRET = "hunter2";

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final RecordingBean bean = new RecordingBean();
    private final Object gizmos = Proxy.newProxyInstance(GizmoRepository.class.getClassLoader(),
            new Class<?>[] {GizmoRepository.class}, bean);

    /** The bean of GizmoRepository: records each call and answers what {@link #answers} holds for its name. */
    static final class RecordingBean implements InvocationHandler {

        /** The answer that returns the call's first argument. */
        static final Object ECHO = new Object();

        final List<String> methods = new ArrayList<>();
        final List<Object[]> arguments = new ArrayList<>();
        final Map<String, Object> answers = new HashMap<>();
        RuntimeException failure;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            methods.add(method.getName());
            Object[] given = args == null ? new Object[0] : args;
            arguments.add(given);
            if (failure != null) {
                throw failure;
            }
            Object answer = answers.get(method.getName());
            return answer == ECHO ? given[0] : answer;
        }
    }

    private RepositoryActions build(TransactionRunner transactions, Predicate<Method> accessible, int max) {
        return RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), type -> {
            if (type == GizmoRepository.class) {
                return gizmos;
            }
            throw new BeanLookup.NoBean();
        }, transactions, RunFixtures::model, accessible, max);
    }

    private RepositoryActions build() {
        return build(new TransactionRunner(new JtaDemarcation(() -> manager)), method -> true,
                RepositoryActions.MAX_ACTIONS);
    }

    private static PanelAction action(RepositoryActions actions, String id) {
        return actions.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    private static ActionResult run(RepositoryActions actions, String id, String arguments, String transaction) {
        Map<String, String> given = new HashMap<>();
        given.put("arguments", arguments);
        if (transaction != null) {
            given.put("transaction", transaction);
        }
        return action(actions, id).call().apply(given);
    }

    private static RecordedSample sample(RepositoryActions actions) {
        RecordedSample sample = new RecordedSample();
        actions.sample(sample);
        return sample;
    }

    private static Gizmo bolt() {
        return RunFixtures.gizmo(1L, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));
    }

    @Test
    void oneActionPerRunnableMethodIdsByRepositoryAndMethodKeys() {
        List<String> ids = build().actions().stream().map(PanelAction::id).toList();

        assertEquals(List.of("m.gizmo-repository.add", "m.gizmo-repository.change",
                "m.gizmo-repository.count-by-stock-greate", "m.gizmo-repository.delete-by-name",
                "m.gizmo-repository.empty", "m.gizmo-repository.find-by-name", "m.gizmo-repository.keep",
                "m.gizmo-repository.purge", "m.gizmo-repository.remove", "m.gizmo-repository.search",
                "m.gizmo-repository.search-2", "m.gizmo-repository.delete", "m.gizmo-repository.delete-by-id",
                "m.gizmo-repository.find-all", "m.gizmo-repository.find-by-id", "m.gizmo-repository.save",
                "m.part-repository.find-by-label", "m.part-repository.delete", "m.part-repository.delete-by-id",
                "m.part-repository.find-all", "m.part-repository.find-by-id", "m.part-repository.save",
                "m.report-queries.gizmo-count"), ids);
        for (String id : ids) {
            assertDoesNotThrow(() -> PanelSample.requireKey(id), id);
        }
    }

    @Test
    void oneGroupPerRepositoryLabelledByMethodName() {
        RepositoryActions actions = build();

        assertEquals(List.of("GizmoRepository", "PartRepository", "ReportQueries"),
                actions.actions().stream().map(PanelAction::group).distinct().toList());
        assertEquals("findById", action(actions, "m.gizmo-repository.find-by-id").label());
        assertEquals("search(String)", action(actions, "m.gizmo-repository.search").label());
        assertEquals("search(String, int)", action(actions, "m.gizmo-repository.search-2").label());
        assertEquals("JDQL: FROM Gizmo WHERE name LIKE :pattern\nsearch(pattern: String) → List<Gizmo>",
                action(actions, "m.gizmo-repository.search").description());
        assertEquals("findById(id: Long) → Optional<Gizmo>\ninherited from BasicRepository",
                action(actions, "m.gizmo-repository.find-by-id").description());
    }

    @Test
    void aWriteAsksFirstAndOffersRollbackThenCommit() {
        RepositoryActions actions = build();

        PanelAction save = action(actions, "m.gizmo-repository.save");
        assertEquals("Runs GizmoRepository.save against the database.", save.confirmation());
        assertEquals(List.of("arguments", "transaction"),
                save.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals(List.of("rollback", "commit"), save.arguments().get(1).allowedValues());

        PanelAction find = action(actions, "m.gizmo-repository.find-by-id");
        assertNull(find.confirmation());
        assertEquals(List.of("arguments"), find.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}",
                find.arguments().getFirst().schema());
    }

    @Test
    void withoutATransactionManagerAWriteCanOnlyBeCommitted() {
        RepositoryActions actions = build(TransactionRunner.NONE, method -> true, RepositoryActions.MAX_ACTIONS);
        bean.answers.put("save", RecordingBean.ECHO);

        assertEquals(List.of("commit"), action(actions, "m.gizmo-repository.save").arguments().get(1).allowedValues());
        assertEquals("1 row · committed",
                run(actions, "m.gizmo-repository.save", "{\"entity\":{\"name\":\"nut\"}}", "commit").summary());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void theMethodsThatCannotRunAreListedWithTheirReason() {
        Table table = (Table) sample(build()).value("not-runnable");

        assertEquals(List.of("repository", "method", "reason"), table.columns());
        assertEquals(List.of(
                List.of("GizmoRepository", "findByLevel(Level, PageRequest)",
                        "parameter page: PageRequest is not supported"),
                List.of("GizmoRepository", "findByNameIn(List)", "parameter names: List is not supported"),
                List.of("GizmoRepository", "findAll(PageRequest, Order)",
                        "parameter pageRequest: PageRequest is not supported"),
                List.of("PartRepository", "findAll(PageRequest, Order)",
                        "parameter pageRequest: PageRequest is not supported")), table.rows());
    }

    @Test
    void aPackageNotOpenToVidocqMakesNoAction() {
        RepositoryActions actions = build(TransactionRunner.NONE, method -> false, RepositoryActions.MAX_ACTIONS);

        assertEquals(List.of(), actions.actions());
        Table table = (Table) sample(actions).value("not-runnable");
        assertEquals(List.of("GizmoRepository", "add(Gizmo)",
                "package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev not open to Vidocq"),
                table.rows().getFirst());
        assertNull(sample(actions).value("calls"), "no action, no history");
    }

    @Test
    void pastTheConsolesLimitTheRestIsCounted() {
        RepositoryActions actions = build(TransactionRunner.NONE, method -> true, 5);

        assertEquals(5, actions.actions().size());
        assertEquals(new Text("and 18 more methods"), sample(actions).value("more-methods"));
    }

    @Test
    void aReadRunsWithoutATransactionAndAnswersJson() {
        RepositoryActions actions = build();
        bean.answers.put("findById", Optional.of(bolt()));

        ActionResult result = run(actions, "m.gizmo-repository.find-by-id", "{\"id\":1}", null);

        assertFalse(result.error());
        assertEquals("1 row", result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertEquals("{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":\"2026-10-01\","
                + "\"price\":2.50}", result.body());
        assertEquals("{\"method\":\"GizmoRepository.findById(Long)\",\"arguments\":[{\"name\":\"id\","
                + "\"type\":\"Long\",\"value\":1}],\"transaction\":\"none\"}", result.details());
        assertArrayEquals(new Object[] {1L}, bean.arguments.getFirst());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void aListAnswerSaysHowLongItTook() {
        RepositoryActions actions = build();
        bean.answers.put("findByName", List.of(bolt(), bolt()));

        String summary = run(actions, "m.gizmo-repository.find-by-name", "{\"name\":\"bolt\"}", null).summary();

        assertTrue(summary.matches("2 rows in \\d+ ms"), summary);
    }

    @Test
    void aWriteInRollbackRollsBackAfterTheCall() {
        RepositoryActions actions = build();
        bean.answers.put("save", RecordingBean.ECHO);

        ActionResult result = run(actions, "m.gizmo-repository.save", "{\"entity\":{\"name\":\"nut\",\"stock\":1}}",
                "rollback");

        assertEquals("1 row · rolled back", result.summary());
        assertEquals(List.of("begin", "rollback"), manager.events);
        Gizmo saved = (Gizmo) bean.arguments.getFirst()[0];
        assertEquals("nut", saved.name());
        assertEquals(1, saved.stock());
        assertNull(saved.id());
        assertTrue(result.details().endsWith("\"transaction\":\"rollback\"}"), result.details());
    }

    @Test
    void aWriteCommitsWhenAsked() {
        ActionResult result = run(build(), "m.gizmo-repository.delete-by-id", "{\"id\":3}", "commit");

        assertEquals("done · committed", result.summary());
        assertNull(result.body());
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void aFailingMethodRollsBackAndShowsItsClassAndItsMaskedMessage() {
        RepositoryActions actions = build();
        bean.failure = new IllegalStateException("connection to jdbc:h2:tcp://sa:" + SECRET + "@db/x refused");

        ActionResult result = run(actions, "m.gizmo-repository.save", "{\"entity\":{\"name\":\"nut\"}}", "commit");

        String expected = "java.lang.IllegalStateException: connection to jdbc:h2:tcp://***:***@db/x refused";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events, "rolled back although commit was asked");
        Table calls = (Table) sample(actions).value("calls");
        assertEquals("error: " + expected, calls.rows().getFirst().get(2));
        assertFalse(calls.toString().contains(SECRET));
    }

    @Test
    void aValueThatDoesNotConvertFailsBeforeAnyCall() {
        RepositoryActions actions = build();

        assertEquals("id: not an integer",
                run(actions, "m.gizmo-repository.find-by-id", "{\"id\":\"one\"}", null).summary());
        assertEquals("id: missing", run(actions, "m.gizmo-repository.find-by-id", "{}", null).summary());
        ActionResult write = run(actions, "m.gizmo-repository.save", "{\"entity\":{\"stock\":null}}", "rollback");
        assertTrue(write.error());
        assertEquals("entity.stock: null is not allowed for int", write.summary());
        assertEquals(List.of(), bean.methods, "nothing invoked");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void aRepositoryWithoutABeanSaysSo() {
        ActionResult result = run(build(), "m.part-repository.find-by-label", "{\"label\":\"x\"}", null);

        assertTrue(result.error());
        assertEquals("no bean for PartRepository", result.summary());
    }

    @Test
    void aStreamThatFailsIsAnErrorAndIsClosed() {
        RepositoryActions actions = build();
        AtomicBoolean closed = new AtomicBoolean();
        bean.answers.put("findAll", Stream.<Gizmo>generate(() -> {
            throw new IllegalStateException("connection lost");
        }).onClose(() -> closed.set(true)));

        ActionResult result = run(actions, "m.gizmo-repository.find-all", "{}", null);

        assertTrue(result.error());
        assertEquals("java.lang.IllegalStateException: connection lost", result.summary());
        assertTrue(closed.get());
    }

    @Test
    void eachCallIsKeptWithItsReplay() {
        RepositoryActions actions = build();
        bean.answers.put("save", RecordingBean.ECHO);
        run(actions, "m.gizmo-repository.save", "{\"entity\": {\"name\": \"nut\", \"stock\": 1}}", "rollback");

        Table calls = (Table) sample(actions).value("calls");

        List<String> row = calls.rows().getFirst();
        assertEquals(CallHistory.COLUMNS, calls.columns());
        assertEquals("save", row.get(1));
        assertEquals("1 row · rolled back", row.get(2));
        assertEquals("{\"entity\":{\"name\":\"nut\",\"stock\":1}}", row.get(4), "the JSON sent, compact");
        assertEquals("m.gizmo-repository.save {\"arguments\":{\"entity\":{\"name\":\"nut\",\"stock\":1}},"
                + "\"transaction\":\"rollback\"}", row.get(5));
    }

    @Test
    void longNamesAreCutFromTheLeftAndKeptApart() {
        Set<String> used = new HashSet<>();
        String name = "io.vidocq.examples.shop.inventory.persistence.GizmoRepository";

        String first = RepositoryActions.group(name, used);
        String second = RepositoryActions.group(name, used);

        assertEquals(40, first.length());
        assertTrue(first.startsWith("…") && first.endsWith("persistence.GizmoRepository"), first);
        assertTrue(second.endsWith(" 2") && second.length() <= 40 && !second.equals(first), second);
        assertEquals("GizmoRepository", RepositoryActions.group("GizmoRepository", used));
    }
}
