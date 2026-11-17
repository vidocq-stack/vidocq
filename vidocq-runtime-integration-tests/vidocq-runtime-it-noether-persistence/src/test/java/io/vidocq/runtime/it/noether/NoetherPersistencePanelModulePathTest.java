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

import io.vidocq.runtime.core.VidocqBootstrap;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev console's Noether Persistence panel on a booted application. The panel is the companion module that
 * {@code vidocq:dev} adds, found as a {@link LivePanel} service, and each tab's action runs on the units this boot
 * started, on H2: {@code archive} is JTA, its work in the transactions of Mansart's transaction manager, and
 * {@code drafts} is RESOURCE_LOCAL, its work in the unit's own transactions.
 *
 * <p>The application runs on the module path; the companion, which it does not require, is on the class path here
 * (see the pom's {@code argLine}). Its own tests check its module: the service it provides, and the units it reads
 * through the extension's qualified export.
 */
class NoetherPersistencePanelModulePathTest {

    private static final String ARCHIVE = "archive";
    private static final String DRAFTS = "drafts";
    private static final String STATEMENT = "statement";
    private static final String TRANSACTION = "transaction";

    private static VidocqBootstrap bootstrap;
    private static LivePanel panel;

    @BeforeAll
    static void bootAndFindThePanel() {
        bootstrap = VidocqBootstrap.create().configure().start();
        NoetherPersistencePanelModulePathTest.class.getModule().addUses(LivePanel.class);
        panel = ServiceLoader.load(LivePanel.class).stream().map(ServiceLoader.Provider::get)
                .filter(found -> found.id().equals("noether-persistence")).findFirst()
                .orElseThrow(() -> new AssertionError("no noether-persistence panel among the LivePanel services"));
        panel.start(beansOnly());
        inDrafts(em -> {
            em.persist(new Draft(1L, "first", "A1"));
            em.persist(new Draft(2L, "second", "B2"));
            em.persist(new Draft(3L, "third", "C3"));
        });
    }

    @AfterAll
    static void shutdown() {
        if (panel != null) {
            panel.stop();
        }
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    /** The context the console gives a panel at its start, of which this panel reads the bean manager alone. */
    private static ExtensionContext beansOnly() {
        return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
                new Class<?>[] {ExtensionContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("beanManager")) {
                        return CDI.current().getBeanManager();
                    }
                    throw new UnsupportedOperationException(method.getName() + ": not given to the panel here");
                });
    }

    /** The factory of the unit {@code drafts}: its bean is named after it. */
    private static EntityManagerFactory drafts() {
        BeanManager beans = CDI.current().getBeanManager();
        Bean<?> bean = beans.resolve(beans.getBeans(DRAFTS));
        return (EntityManagerFactory) beans.getReference(bean, EntityManagerFactory.class,
                beans.createCreationalContext(bean));
    }

    /** Runs {@code work} in a transaction of the unit {@code drafts}, and commits it. */
    private static void inDrafts(Consumer<EntityManager> work) {
        try (EntityManager em = drafts().createEntityManager()) {
            em.getTransaction().begin();
            work.accept(em);
            em.getTransaction().commit();
        }
    }

    private static long draftsFrom(long id) {
        try (EntityManager em = drafts().createEntityManager()) {
            return em.createQuery("SELECT COUNT(d) FROM Draft d WHERE d.id >= :id", Long.class).setParameter("id", id)
                    .getSingleResult();
        }
    }

    private static Archive archive() {
        return CDI.current().select(Archive.class).get();
    }

    private static PanelAction action(String id) {
        return panel.actions().stream().filter(found -> found.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    /**
     * The action the page offers on {@code unit}: {@code action}, or, for a tab that gives each unit its own,
     * {@code action} followed by the unit's number, whose unit argument allows that unit.
     */
    private static PanelAction offered(String action, String unit) {
        return panel.actions().stream()
                .filter(found -> found.id().equals(action) || found.id().startsWith(action + "."))
                .filter(found -> found.arguments().getFirst().allowedValues().contains(unit)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + action + " on " + unit));
    }

    private static ActionResult run(String action, String unit, Map<String, String> arguments) {
        Map<String, String> given = new HashMap<>(arguments);
        given.put("unit", unit);
        return offered(action, unit).call().apply(given);
    }

    private static String statement(String query) {
        return "{\"query\":\"" + query + "\"}";
    }

    @Test
    void theDropdownOffersEveryUnitOfTheBoot() {
        assertEquals(Set.of("ledger", ARCHIVE, "catalog", "journal", DRAFTS),
                Set.copyOf(action("model").arguments().getFirst().allowedValues()));
    }

    @Test
    void theModelDrawsTheEntitiesOfTheUnit() {
        ActionResult model = run("model", ARCHIVE, Map.of());

        assertFalse(model.error(), model.summary());
        assertEquals(ActionResult.GRAPH, model.contentType());
        assertTrue(model.body().contains("\"id\":\"entity:Note\""), model.body());
    }

    @Test
    void theEditorOfEachUnitKnowsItsOwnEntities() {
        String schema = offered("jpql.query", DRAFTS).arguments().get(1).schema();
        Matcher language = Pattern.compile("\"x-language\":\"(jpql\\.[0-9]+)\"").matcher(schema);
        assertTrue(language.find(), schema);

        String json = panel.languages().stream().filter(found -> found.id().equals(language.group(1))).findFirst()
                .orElseThrow(() -> new AssertionError("no language " + language.group(1))).json();
        assertTrue(json.contains("\"Draft\""), json);
        assertFalse(json.contains("\"Note\""), json);
        assertEquals(5, panel.languages().size(), "a language per unit");
    }

    @Test
    void aQueryOfAnEntityShowsTheValuesItHolds() {
        ActionResult rows = run("jpql.query", DRAFTS, Map.of(STATEMENT,
                statement("SELECT d FROM Draft d WHERE d.id < 10 ORDER BY d.id")));

        assertFalse(rows.error(), rows.summary() + "\n" + rows.body());
        assertEquals(ActionResult.ROWS, rows.contentType());
        assertTrue(rows.summary().startsWith("3 rows in "), rows.summary());
        assertTrue(rows.body().contains("\"first\"") && rows.body().contains("\"C3\""), rows.body());
    }

    @Test
    void aWriteOnAJtaUnitRollsBackOrCommitsInTheApplicationsTransactions() {
        archive().record("kept-1");
        archive().record("kept-2");
        String delete = statement("DELETE FROM Note n WHERE n.id LIKE 'kept-%'");

        ActionResult rolledBack = run("jpql.write", ARCHIVE, Map.of(STATEMENT, delete, TRANSACTION, "rollback"));

        assertEquals("2 rows deleted · rolled back", rolledBack.summary(), rolledBack.body());
        assertEquals(2, archive().count("kept-1") + archive().count("kept-2"));

        ActionResult committed = run("jpql.write", ARCHIVE, Map.of(STATEMENT, delete, TRANSACTION, "commit"));

        assertEquals("2 rows deleted · committed", committed.summary(), committed.body());
        assertEquals(0, archive().count("kept-1") + archive().count("kept-2"));
    }

    @Test
    void aWriteOnAResourceLocalUnitRollsBackOrCommitsInItsOwnTransaction() {
        inDrafts(em -> {
            em.persist(new Draft(10L, "tenth", "J10"));
            em.persist(new Draft(11L, "eleventh", "K11"));
        });
        String delete = "{\"query\":\"DELETE FROM Draft d WHERE d.id >= :from\",\"params\":{\"from\":10}}";

        ActionResult rolledBack = run("jpql.write", DRAFTS, Map.of(STATEMENT, delete, TRANSACTION, "rollback"));

        assertEquals("2 rows deleted · rolled back", rolledBack.summary(), rolledBack.body());
        assertEquals(2, draftsFrom(10));

        ActionResult committed = run("jpql.write", DRAFTS, Map.of(STATEMENT, delete, TRANSACTION, "commit"));

        assertEquals("2 rows deleted · committed", committed.summary(), committed.body());
        assertEquals(0, draftsFrom(10));
    }

    @Test
    void explorePagesThroughAnEntityAndItsRowsOpenJpql() {
        ActionResult page = run("explore.page", DRAFTS, Map.of("entity", "Draft", "size", "20", "page", "1"));

        assertFalse(page.error(), page.summary() + "\n" + page.body());
        assertTrue(page.summary().matches("[0-9]+ rows of Draft, page 1, in [0-9]+ ms"), page.summary());
        assertTrue(page.body().contains("\"first\""), page.body());
        assertTrue(page.body().contains("DELETE FROM Draft e WHERE e.id IN ("), page.body());
        Matcher write = Pattern.compile("\"action\":\"(jpql\\.write\\.[0-9]+)\"").matcher(page.body());
        assertTrue(write.find(), page.body());
        assertEquals(offered("jpql.write", DRAFTS).id(), write.group(1), "the menu fills the JPQL tab of the unit");
    }

    @Test
    void eachDeclaredIndexIsFoundInTheDatabase() {
        ActionResult drafts = run("indexes.compare", DRAFTS, Map.of());
        ActionResult archive = run("indexes.compare", ARCHIVE, Map.of());

        assertEquals("2 same", drafts.summary(), drafts.body());
        assertTrue(drafts.body().contains("\"idx_draft_title\""), drafts.body());
        assertEquals("no index declared or found", archive.summary(), archive.body());
        assertEquals("2 indexes on 1 table", run("indexes.declared", DRAFTS, Map.of()).summary());
    }

    @Test
    void theScriptsCreateAndDropTheTablesOfTheUnit() {
        ActionResult create = run("ddl.create", DRAFTS, Map.of());
        ActionResult drop = run("ddl.drop", DRAFTS, Map.of());

        assertEquals(ActionResult.SQL, create.contentType());
        assertTrue(create.body().toUpperCase(Locale.ROOT).contains("CREATE TABLE DRAFT"), create.body());
        assertTrue(create.body().contains("idx_draft_title"), create.body());
        assertTrue(drop.body().toUpperCase(Locale.ROOT).contains("DROP TABLE"), drop.body());
    }
}
