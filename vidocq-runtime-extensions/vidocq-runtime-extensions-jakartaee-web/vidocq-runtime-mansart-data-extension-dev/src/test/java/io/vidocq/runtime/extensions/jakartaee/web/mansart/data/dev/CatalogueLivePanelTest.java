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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Absent;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Text;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Method;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Repository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mansart-data} section, live: {@link CatalogueLivePanel} reads {@link MansartDataLive}, the holder the
 * runtime extension publishes at the end of {@code onStart} and clears first thing in {@code onStop}.
 */
class CatalogueLivePanelTest {

    private final CatalogueLivePanel panel = new CatalogueLivePanel();

    @AfterEach
    void clear() {
        panel.stop();
        MansartDataLive.clear();
    }

    private RecordedSample sample() {
        RecordedSample sample = new RecordedSample();
        panel.sample(sample);
        return sample;
    }

    /** Two entities, one whose model failed, three repositories of theirs and one with no primary entity. */
    private static MansartDataCatalogue catalogue() {
        Entity broken = new Entity("Broken", "com.acme.Broken", "", List.of(), "com.acme.MappingException");
        Entity task = new Entity("Task", "com.acme.Task", "shop.tasks", List.of(
                new Column("id", "id", "Long", "id, generated", false, true),
                new Column("title", "title", "String", "", false, false),
                new Column("project", "project_id", "Project", "→ Project", true, false)), null);
        Repository brokenRepository = new Repository("BrokenRepository", "com.acme.BrokenRepository",
                "com.acme.Broken", "Broken", "Long", List.of(), 0, "inherits BasicRepository: delete, findAll");
        Repository reports = new Repository("ReportQueries", "com.acme.ReportQueries", null, null, null,
                List.of(new Method("taskCount", "JDQL", "SELECT count(this) FROM Task", "", "long")), 1, "");
        Repository archive = new Repository("TaskArchive", "com.acme.TaskArchive", "com.acme.Task", "Task", "Long",
                List.of(new Method("findByProject", "derived", "", "arg0: String", "List<Task>")), 1, "");
        Repository tasks = new Repository("TaskRepository", "com.acme.TaskRepository", "com.acme.Task", "Task",
                "Long", List.of(
                        new Method("countByProject", "derived", "", "project: String", "long"),
                        new Method("searchText", "JDQL", "FROM Task WHERE title LIKE :pattern", "pattern: String",
                                "List<Task>")), 5,
                "inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll");
        return new MansartDataCatalogue(List.of(broken, task), List.of(brokenRepository, reports, archive, tasks),
                3, 4, 9);
    }

    @Test
    void itMakesTheMansartDataSectionLiveWithNoChartAndNoAction() {
        assertEquals("mansart-data", panel.id());
        assertEquals(List.of(), panel.charts());
        assertEquals(List.of(), panel.actions());
    }

    @Test
    void withNoCatalogueTheSampleSaysSo() {
        RecordedSample sample = sample();

        assertEquals(List.of("catalogue"), sample.keys());
        assertEquals(new Absent("no catalogue yet"), sample.value("catalogue"));
        assertEquals(List.of(), sample.groupNames());
    }

    @Test
    void oneGroupPerEntityInOrderThenTheOtherRepositories() {
        MansartDataLive.publish(catalogue());

        RecordedSample sample = sample();

        assertEquals(List.of("Broken", "Task", "Other repositories"), sample.groupNames());
        assertEquals(List.of("more-entities"), sample.keys());
        assertEquals(new Text("and 1 more"), sample.value("more-entities"));
    }

    @Test
    void anEntityGroupHoldsItsTableItsColumnsAndItsRepositories() {
        MansartDataLive.publish(catalogue());

        RecordedSample task = sample().written("Task");

        assertEquals(List.of("table", "columns", "task-archive", "task-repository", "task-repository.inherits",
                "task-repository.more"), task.keys());
        assertEquals(new Text("shop.tasks"), task.value("table"));
        assertEquals(new Table(List.of("field", "column", "type", "key", "nullable", "unique"), List.of(
                List.of("id", "id", "Long", "id, generated", "", "yes"),
                List.of("title", "title", "String", "", "", ""),
                List.of("project", "project_id", "Project", "→ Project", "yes", ""))), task.value("columns"));
        assertEquals(new Table(List.of("method", "kind", "query", "parameters", "returns"), List.of(
                List.of("countByProject", "derived", "", "project: String", "long"),
                List.of("searchText", "JDQL", "FROM Task WHERE title LIKE :pattern", "pattern: String",
                        "List<Task>"))), task.value("task-repository"));
        // the key already says "inherits": the value does not say it again
        assertEquals(new Text("BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll"),
                task.value("task-repository.inherits"));
        assertEquals(new Text("and 3 more"), task.value("task-repository.more"));
    }

    @Test
    void anEntityWhoseModelFailedSaysWhyInsteadOfItsColumns() {
        MansartDataLive.publish(catalogue());

        RecordedSample broken = sample().written("Broken");

        assertEquals(List.of("model", "broken-repository", "broken-repository.inherits"), broken.keys());
        assertEquals(new Text("unavailable: com.acme.MappingException"), broken.value("model"));
        assertEquals(new Table(List.of("method", "kind", "query", "parameters", "returns"), List.of()),
                broken.value("broken-repository"));
    }

    @Test
    void theOtherRepositoriesHaveTheirOwnGroup() {
        MansartDataLive.publish(catalogue());

        RecordedSample others = sample().written("Other repositories");

        assertEquals(List.of("report-queries"), others.keys());
        assertEquals(new Table(List.of("method", "kind", "query", "parameters", "returns"),
                        List.of(List.of("taskCount", "JDQL", "SELECT count(this) FROM Task", "", "long"))),
                others.value("report-queries"));
    }

    @Test
    void afterTheExtensionStoppedThereIsNoCatalogueAgain() {
        MansartDataLive.publish(catalogue());
        MansartDataLive.clear();

        assertEquals(new Absent("no catalogue yet"), sample().value("catalogue"));
    }

    @Test
    void keysAreValidAndDistinct() {
        Set<String> used = new HashSet<>(Set.of("table", "columns", "model"));
        List<String> keys = List.of(
                CatalogueLivePanel.key("TaskRepository", used),
                CatalogueLivePanel.key("TaskEventRepository", used),
                CatalogueLivePanel.key("Columns", used),
                CatalogueLivePanel.key("TaskRepository", used),
                CatalogueLivePanel.key("_Weird", used),
                CatalogueLivePanel.key("9Lives", used),
                CatalogueLivePanel.key("com.acme.orders.TaskRepository", used),
                CatalogueLivePanel.key("AVeryLongRepositoryNameThatGoesOnAndOnForeverAndEver", used));

        assertEquals(List.of("task-repository", "task-event-repository", "columns-2", "task-repository-2", "weird",
                "r-9-lives", "com-acme-orders-task-repository", "avery-long-repository-name-that"), keys);
        for (String key : keys) {
            assertDoesNotThrow(() -> PanelSample.requireKey(key + ".inherits"), key);
            assertTrue(key.length() <= 31, key);
        }
    }

    @Test
    void aKeyFitsTheRoomItIsGiven() {
        Set<String> used = new HashSet<>();

        assertEquals("count-by-stock-greate", CatalogueLivePanel.key("countByStockGreaterThan", used, 21));
        assertEquals("count-by-stock-grea-2", CatalogueLivePanel.key("countByStockGreaterThan", used, 21));
    }

    @Test
    void withoutAStartThereIsNoAction() {
        publishRunFixtures();

        assertEquals(List.of(), panel.actions());
    }

    @Test
    void startKeepsTheBeanManagerTheActionsAreBuiltFrom() {
        publishRunFixtures();
        panel.start(new FakeContext(emptyBeanManager()));

        List<PanelAction> actions = panel.actions();

        assertEquals(25, actions.size());
        assertEquals(List.of("jdql.query", "jdql.write"),
                actions.subList(23, 25).stream().map(PanelAction::id).toList(), "the JDQL tab, last");
        assertEquals(List.of("commit"), actions.get(24).arguments().get(1).allowedValues(),
                "no TransactionManager bean");
        assertEquals("no RepositoryRuntime bean",
                actions.get(23).call().apply(Map.of("statement", "{\"query\":\"FROM Gizmo\"}")).summary());
        PanelAction save = actions.stream().filter(a -> a.id().equals("m.gizmo-repository.save")).findFirst()
                .orElseThrow();
        assertEquals(List.of("commit"), save.arguments().get(1).allowedValues(), "no TransactionManager bean");
        assertEquals("no bean for GizmoRepository", save.call()
                .apply(Map.of("arguments", "{\"entity\":{}}", "transaction", "commit")).summary());
    }

    @Test
    void theMonitoringTabAlsoListsWhatCannotRunAndTheCalls() {
        publishRunFixtures();
        panel.actions(type -> {
            throw new BeanLookup.NoBean();
        }, TransactionRunner.NONE, RunFixtures::model, method -> true);

        RecordedSample sample = sample();

        assertEquals(List.of("not-runnable", "calls"), sample.keys());
        assertEquals(List.of("Gizmo", "Part", "Other repositories"), sample.groupNames(), "the catalogue as before");
    }

    @Test
    void stopDropsTheActionsAndTheirCalls() {
        publishRunFixtures();
        panel.start(new FakeContext(emptyBeanManager()));
        panel.actions();

        panel.stop();

        assertEquals(List.of(), panel.actions());
        assertEquals(List.of(), sample().keys());
    }

    private static void publishRunFixtures() {
        MansartDataLive.publish(RunFixtures.catalogue());
        MansartDataLive.publishRepositories(RunFixtures.REPOSITORIES);
    }

    /** A bean manager with no bean at all: no repository bean, no TransactionManager. */
    private static BeanManager emptyBeanManager() {
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getBeans")) {
                        return Set.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** What {@code start} reads: the bean manager only. */
    private record FakeContext(BeanManager beanManager) implements ExtensionContext {

        @Override
        public VaubanContainer container() {
            throw new UnsupportedOperationException("start reads the bean manager only");
        }

        @Override
        public VidocqConfiguration configuration() {
            throw new UnsupportedOperationException("start reads the bean manager only");
        }

        @Override
        public VidocqConfig config() {
            throw new UnsupportedOperationException("start reads the bean manager only");
        }
    }
}
