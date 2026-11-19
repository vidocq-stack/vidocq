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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.UNIT;
import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.attribute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a query's results show: a column per value of the select list, an entity as the values it holds. */
class ResultRowsTest {

    enum Status { OPEN }

    static final Function<Object, Object> NO_IDS = item -> {
        throw new AssertionError("no entity in this test");
    };

    /** An entity of the test's own unit, whose class the results carry. */
    record Item(long id, String label) {}

    static final UnitDescription ITEMS = new UnitDescription("items", List.of(
            new Entity("Item", Item.class.getName(), "ITEM", null, Inheritance.NONE, false, List.of(
                    attribute("id", "java.lang.Long", Item.class.getName(), Kind.ID, List.of("ID"), null, null, null),
                    attribute("label", "java.lang.String", Item.class.getName(), Kind.BASIC, List.of("LABEL"), null,
                            null, null)))), List.of(), List.of(), List.of());

    /** The results of a query whose select list has several items: an array per row. */
    static List<Object> results(Object[]... rows) {
        return List.of((Object[]) rows);
    }

    @Test
    void anArrayGivesAColumnPerItemNamedByTheSelectList() {
        ResultRows.Table table = ResultRows.table("SELECT n.title, COUNT(n) AS total FROM Note n GROUP BY n.title",
                results(new Object[] {"a", 2L}, new Object[] {"b", 1L}), null, UNIT, NO_IDS);

        assertEquals(List.of(new Column("n.title", "string"), new Column("total", "integer")), table.columns());
        assertEquals(List.of(List.of("a", 2L), List.of("b", 1L)), table.rows());
    }

    @Test
    void aValueIsOneColumnNamedByItsExpression() {
        ResultRows.Table table = ResultRows.table("select distinct count(n) from Note n", List.of(3L), null, UNIT,
                NO_IDS);

        assertEquals(List.of(new Column("count(n)", "integer")), table.columns());
        assertEquals(List.of(List.of(3L)), table.rows());
    }

    @Test
    void aCommaInParenthesesOrQuotesSplitsNoItem() {
        ResultRows.Table table = ResultRows.table("SELECT CONCAT(n.title, ', ', n.id), n.id FROM Note n",
                results(new Object[] {"a, 1", 1L}), null, UNIT, NO_IDS);

        assertEquals(List.of("CONCAT(n.title, ', ', n.id)", "n.id"), table.columns().stream().map(Column::name)
                .toList());
    }

    @Test
    void valuesThatTheSelectListDoesNotNameAreNumbered() {
        ResultRows.Table table = ResultRows.table("FROM Note n", results(new Object[] {1L, true}), null,
                UNIT, NO_IDS);

        assertEquals(List.of(new Column("1", "integer"), new Column("2", "boolean")), table.columns());
    }

    @Test
    void aCellIsWhatATableOfRowsWrites() {
        ResultRows.Table table = ResultRows.table("SELECT a, b, c, d, e, f FROM Note n", results(
                new Object[] {Status.OPEN, LocalDate.of(2026, 11, 16), new byte[3], 'x', null, 2.5}),
                null, UNIT, NO_IDS);

        assertEquals(Arrays.asList("OPEN", "2026-11-16", "3 bytes", "x", null, 2.5), table.rows().getFirst());
        assertEquals(List.of("string", "string", "string", "string", "", "number"),
                table.columns().stream().map(Column::type).toList());
    }

    @Test
    void anEntityIsTheValuesOfItsProjectionInTheOrderFound() {
        List<String> calls = new ArrayList<>();
        Map<String, Object> bound = new HashMap<>();
        List<String> jpql = new ArrayList<>();
        Query query = Stubs.of(Query.class, calls, Map.of(
                "setParameter", args -> {
                    bound.put((String) args[0], args[1]);
                    return Stubs.SELF;
                },
                "getResultList", args -> List.of(new Object[] {1L, "one"}, new Object[] {2L, "two"})));
        EntityManager em = Stubs.of(EntityManager.class, calls, Map.of("createQuery", args -> {
            jpql.add((String) args[0]);
            return query;
        }));

        ResultRows.Table table = ResultRows.table("SELECT i FROM Item i", List.of(new Item(2, "two"),
                new Item(1, "one")), em, ITEMS, item -> ((Item) item).id());

        assertEquals(List.of("SELECT e.id, e.label FROM Item e WHERE e.id IN :ids"), jpql);
        assertEquals(List.of(2L, 1L), bound.get("ids"));
        assertEquals(List.of(new Column("id", "integer"), new Column("label", "string")), table.columns());
        assertEquals(List.of(List.of(2L, "two"), List.of(1L, "one")), table.rows());
    }

    @Test
    void anEntityInAnArrayIsItsNameAndId() {
        ResultRows.Table table = ResultRows.table("SELECT i, i.label FROM Item i",
                results(new Object[] {new Item(7, "seven"), "seven"}), null, ITEMS,
                item -> ((Item) item).id());

        assertEquals(List.of("Item#7", "seven"), table.rows().getFirst());
    }

    @Test
    void pastAHundredRowsTheTableSaysThereIsMore() {
        List<Object> found = new ArrayList<>(IntStream.range(0, ResultRows.MAX_ROWS + 1).mapToObj(i -> (Object) (long) i)
                .toList());

        ActionResult result = ResultRows.shown(ResultRows.table("SELECT n.id FROM Note n", found, null, UNIT,
                NO_IDS), 12);

        assertEquals(ActionResult.ROWS, result.contentType());
        assertEquals("first 100 rows in 12 ms", result.summary());
        assertTrue(result.body().contains("\"more\":true"), result.body());
        assertEquals("1 row in 3 ms", ResultRows.shown(ResultRows.table("SELECT n.id FROM Note n", List.of(1L),
                null, UNIT, NO_IDS), 3).summary());
    }
}
