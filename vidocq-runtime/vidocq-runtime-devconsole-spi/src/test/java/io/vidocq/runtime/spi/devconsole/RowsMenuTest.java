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
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.MenuItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The right-click menu a rows result offers on its selected rows, each entry filling another action's form. */
class RowsMenuTest {

    private static final List<Column> COLUMNS = List.of(new Column("id", "BIGINT"), new Column("title", ""));
    private static final List<List<Object>> ROWS = List.of(List.of(1, "a"), List.of(3, "b"));
    private static final MenuItem DELETE = new MenuItem("Delete these rows", "jpql",
            "{\"request\":{\"query\":\"DELETE FROM Note e WHERE e.id IN (${id})\"}}");

    @Test
    void aMenuIsWrittenBetweenColumnsAndRows() {
        ActionResult result = ActionResult.rowsWithMenu("2 rows", COLUMNS, ROWS, false, List.of(DELETE));

        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":\"\"}],"
                + "\"menu\":[{\"label\":\"Delete these rows\",\"action\":\"jpql\",\"arguments\":{\"request\":"
                + "{\"query\":\"DELETE FROM Note e WHERE e.id IN (${id})\"}}}],\"rows\":[[1,\"a\"],[3,\"b\"]],"
                + "\"more\":false}", result.body());
        assertEquals(ActionResult.ROWS, result.contentType());
    }

    @Test
    void noMenuWritesTheBodyOfToday() {
        assertEquals(ActionResult.rows("2 rows", COLUMNS, ROWS, true).body(),
                ActionResult.rowsWithMenu("2 rows", COLUMNS, ROWS, true, List.of()).body());
    }

    @Test
    void anEntryWhoseActionBreaksTheKeyRuleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MenuItem("Delete", "Run JPQL", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new MenuItem(" ", "jpql", "{}"));
        String longLabel = "d".repeat(81);

        assertThrows(IllegalArgumentException.class, () -> new MenuItem(longLabel, "jpql", "{}"));
    }

    @Test
    void argumentsThatAreNoJsonObjectAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MenuItem("Delete", "jpql", "[1]"));
        assertThrows(IllegalArgumentException.class, () -> new MenuItem("Delete", "jpql", "{\"query\":"));
        assertThrows(NullPointerException.class, () -> new MenuItem("Delete", "jpql", null));
    }

    @Test
    void argumentsPastTheReplayCellLimitAreRefused() {
        String query = "{\"q\":\"" + "x".repeat(PanelSample.MAX_REPLAY_CELL) + "\"}";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new MenuItem("Delete", "jpql", query));
        assertTrue(refused.getMessage().contains("4096"), refused.getMessage());
    }

    @Test
    void seventeenEntriesAreRefused() {
        List<MenuItem> menu = IntStream.rangeClosed(0, ActionResult.MAX_MENU)
                .mapToObj(i -> new MenuItem("Entry " + i, "jpql", "{}")).toList();

        assertEquals(16, ActionResult.MAX_MENU);
        assertThrows(IllegalArgumentException.class, () -> ActionResult.rowsWithMenu(null, COLUMNS, ROWS, false, menu));
    }

    @Test
    void aLongMenuHoldsFewerRows() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            rows.add(List.of(i, "row " + i));
        }
        List<MenuItem> menu = IntStream.range(0, ActionResult.MAX_MENU)
                .mapToObj(i -> new MenuItem("Entry " + i, "jpql", "{\"q\":\"" + "x".repeat(4000) + "\"}")).toList();

        int without = ActionResult.rowsHeld(COLUMNS, rows);
        int with = ActionResult.rowsHeld(COLUMNS, rows, menu);
        assertTrue(with < without, with + " rows with the menu, " + without + " without");
        String body = ActionResult.rowsWithMenu(null, COLUMNS, rows, false, menu).body();
        assertTrue(body.length() <= ActionResult.MAX_CONTENT && body.endsWith("],\"more\":true}"), "never cut");
    }
}
