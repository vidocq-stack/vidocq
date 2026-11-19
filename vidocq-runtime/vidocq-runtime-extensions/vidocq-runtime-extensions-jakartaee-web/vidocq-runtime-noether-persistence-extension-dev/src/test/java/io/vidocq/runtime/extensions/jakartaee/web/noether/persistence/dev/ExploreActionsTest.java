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

import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Embeddable;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.MenuItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.UNIT;
import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.attribute;
import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ProjectionTest.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Explore tab: a page of an entity's rows, whose selected rows open a JPQL write. */
class ExploreActionsTest {

    static final String LINE_KEY = "com.acme.LineKey";
    /** The JPQL write of the unit, which the menu of its rows fills. */
    static final String WRITE = "jpql.write.1";

    /** Line, whose id is the embedded LineKey(orderId, no). */
    static final UnitDescription LINES = new UnitDescription("orders", List.of(
            new Entity("Line", "com.acme.Line", "LINE", null, Inheritance.NONE, false, List.of(
                    attribute("key", LINE_KEY, "com.acme.Line", Kind.EMBEDDED_ID, List.of("ORDER_ID", "NO"),
                            LINE_KEY, null, null),
                    attribute("amount", "java.math.BigDecimal", "com.acme.Line", Kind.BASIC, List.of("AMOUNT"),
                            null, null, null)))),
            List.of(new Embeddable(LINE_KEY, List.of(
                    attribute("orderId", "java.lang.Long", LINE_KEY, Kind.BASIC, List.of("ORDER_ID"), null, null,
                            null),
                    attribute("no", "java.lang.Integer", LINE_KEY, Kind.BASIC, List.of("NO"), null, null, null)))),
            List.of(), List.of());

    private final List<String> calls = new ArrayList<>();
    private final Map<String, Object> asked = new HashMap<>();
    private final ExploreActions explore = new ExploreActions(new UnitWork(() -> null));

    private Unit unit(String name, UnitDescription description, List<?> rows) {
        Query query = Stubs.of(Query.class, calls, Map.of(
                "setFirstResult", args -> {
                    asked.put("first", args[0]);
                    return Stubs.SELF;
                },
                "setMaxResults", args -> {
                    asked.put("max", args[0]);
                    return Stubs.SELF;
                },
                "getResultList", args -> rows));
        EntityTransaction transaction = Stubs.of(EntityTransaction.class, calls, Map.of("begin", Stubs.NOTHING,
                "rollback", Stubs.NOTHING, "isActive", args -> false));
        EntityManager em = Stubs.of(EntityManager.class, calls, Map.of(
                "getTransaction", args -> transaction,
                "createQuery", args -> {
                    asked.put("jpql", args[0]);
                    return query;
                },
                "close", Stubs.NOTHING));
        return new Unit(name, false, null, "none", new StubFactory(name, description, null, () -> em));
    }

    private static Map<String, String> page(String entity, String size, String page) {
        return Map.of(ExploreActions.ENTITY, entity, ExploreActions.SIZE, size, ExploreActions.PAGE, page);
    }

    @Test
    void theTabPagesThroughAnyEntityOfTheUnits() {
        PanelAction.Argument unit = new PanelAction.Argument("unit", "Persistence unit", List.of("notes"), null,
                null);

        List<PanelAction> actions = explore.actions(unit, List.of("Author", "Note"), call -> given -> null, ".1");

        assertEquals(List.of("explore.page.1", "explore.export.1"), actions.stream().map(PanelAction::id).toList());
        assertTrue(actions.stream().allMatch(a -> a.group().equals("Explore")));
        assertEquals(List.of("Author", "Note"), actions.getFirst().arguments().get(1).allowedValues());
        assertEquals(List.of("20", "50", "100"), actions.getFirst().arguments().get(2).allowedValues());
    }

    @Test
    void theMenuDeletesOrUpdatesTheSelectedRowsThroughJpql() {
        List<MenuItem> menu = ExploreActions.menu(entity(UNIT, "Note"), Projection.of(entity(UNIT, "Note"), UNIT),
                WRITE);

        assertEquals(List.of(
                new MenuItem("Delete these rows", WRITE,
                        "{\"statement\":{\"query\":\"DELETE FROM Note e WHERE e.id IN (${id})\"}}"),
                new MenuItem("Update these rows", WRITE,
                        "{\"statement\":{\"query\":\"UPDATE Note e SET e.title = e.title WHERE e.id IN (${id})\"}}")),
                menu);
        assertEquals(1, ExploreActions.menu(entity(UNIT, "Tag"), Projection.of(entity(UNIT, "Tag"), UNIT), WRITE)
                        .size(),
                "an entity with nothing but its id has nothing to update");
    }

    @Test
    void aPageIsTheProjectionOrderedByIdFromItsFirstRow() {
        Unit notes = unit("notes", UNIT, ResultRowsTest.results(new Object[] {21L, "a", 4L, null, null},
                new Object[] {22L, "b", null, "Main St", "Paris"}));

        ActionResult result = explore.showPage(notes, page("Note", "20", "2"), WRITE);

        assertFalse(result.error(), result.summary());
        assertEquals("SELECT e.id, e.title, j1.id, e.address.street, e.address.city FROM Note e"
                + " LEFT JOIN e.author j1 ORDER BY e.id", asked.get("jpql"));
        assertEquals(20, asked.get("first"));
        assertEquals(21, asked.get("max"));
        assertTrue(result.summary().matches("2 rows of Note, page 2, in \\d+ ms"), result.summary());
        assertTrue(result.body().contains("\"menu\":[{\"label\":\"Delete these rows\""), result.body());
        assertTrue(calls.contains("rollback"));
    }

    @Test
    void aCompositeIdHasNoMenu() {
        Unit orders = unit("orders", LINES, ResultRowsTest.results(new Object[] {1L, 2, 9.5}));

        ActionResult result = explore.showPage(orders, page("Line", "20", ""), WRITE);

        assertEquals("SELECT e.key.orderId, e.key.no, e.amount FROM Line e ORDER BY e.key.orderId, e.key.no",
                asked.get("jpql"));
        assertEquals(0, asked.get("first"));
        assertFalse(result.body().contains("\"menu\""), result.body());
        assertEquals(List.of(), ExploreActions.menu(LINES.entities().getFirst(),
                Projection.of(LINES.entities().getFirst(), LINES), WRITE));
    }

    @Test
    void aFullPageSaysTheNextOneHasMore() {
        List<Object> rows = new ArrayList<>();
        for (long i = 0; i < 21; i++) {
            rows.add(i);
        }
        ActionResult result = explore.showPage(unit("notes", UNIT, rows), page("Tag", "20", "1"), WRITE);

        assertTrue(result.summary().startsWith("20 rows of Tag, page 1, more on the next page, in "),
                result.summary());
        assertTrue(result.body().contains("\"more\":true"), result.body());
    }

    @Test
    void anEntityOfAnotherUnitOrAPageThatIsNoNumberIsRefused() {
        Unit notes = unit("notes", UNIT, List.of());

        assertEquals("Line is no entity of unit notes",
                explore.showPage(notes, page("Line", "20", "1"), WRITE).summary());
        assertEquals("page: not a page number", explore.showPage(notes, page("Note", "20", "x"), WRITE).summary());
        assertEquals("page: not a page number", explore.showPage(notes, page("Note", "20", "0"), WRITE).summary());
        assertEquals(List.of(), calls);
    }

    @Test
    void theExportIsThePageAsCsv() {
        Unit notes = unit("notes", UNIT, ResultRowsTest.results(new Object[] {1L, "a", null, null, null}));

        ActionResult result = explore.exportPage(notes, page("Note", "50", "1"));

        assertEquals(ActionResult.CSV, result.contentType());
        assertEquals("id,title,author.id,address.street,address.city\n1,a,,,\n", result.body());
        assertEquals(51, asked.get("max"));
        assertEquals("1 row of Note, page 1", result.summary());
    }
}
