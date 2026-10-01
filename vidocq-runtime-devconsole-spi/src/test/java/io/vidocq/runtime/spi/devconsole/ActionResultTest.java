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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an action returns: a summary line, and optionally a body, an error flag and the details of the exchange. */
class ActionResultTest {

    @Test
    void aNullSummaryReadsDoneAndALongOneIsCut() {
        assertEquals("done", ActionResult.of(null).summary());
        String cut = ActionResult.of("x".repeat(250)).summary();
        assertEquals(200, cut.length());
        assertTrue(cut.endsWith("..."), cut);
        assertEquals("y".repeat(200), ActionResult.of("y".repeat(200)).summary());
    }

    @Test
    void aBodyIsTextOrJsonAndDefaultsToText() {
        assertEquals("text/plain", new ActionResult("ok", null, "hello", false, null).contentType());
        assertEquals("application/json", new ActionResult("ok", "application/json", "{}", false, null).contentType());
        assertNull(new ActionResult("ok", "application/json", null, false, null).contentType(), "no body, no type");
        assertThrows(IllegalArgumentException.class, () -> new ActionResult("ok", "text/html", "<b>", false, null));
    }

    @Test
    void aBodyMayBeCsvWhichThePageOffersToDownload() {
        assertEquals("text/csv", ActionResult.CSV);

        ActionResult csv = new ActionResult("2 rows", ActionResult.CSV, "a,b\r\n1,2\r\n", false, null);

        assertEquals("text/csv", csv.contentType());
        assertEquals("a,b\r\n1,2\r\n", csv.body());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ActionResult("ok", "text/csv;charset=utf-8", "a", false, null));
        assertEquals("a result's content type is text/plain, application/json, text/csv or application/x-rows+json",
                refused.getMessage());
    }

    @Test
    void aRowsResultIsItsColumnsItsRowsAndWhetherThereAreMore() {
        ActionResult rows = ActionResult.rows("2 rows in 3 ms",
                List.of(new Column("id", "BIGINT"), new Column("title", "VARCHAR(200)"), new Column("done", "")),
                List.of(List.of(1, "it's \"done\"", true), Arrays.asList(9007199254740993L, "", null)), false);

        assertEquals("application/x-rows+json", ActionResult.ROWS);
        assertEquals(ActionResult.ROWS, rows.contentType());
        assertEquals("2 rows in 3 ms", rows.summary());
        assertFalse(rows.error());
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":"
                + "\"VARCHAR(200)\"},{\"name\":\"done\",\"type\":\"\"}],\"rows\":[[1,\"it's \\\"done\\\"\",true],"
                + "[9007199254740993,\"\",null]],\"more\":false}", rows.body(), "a long is written whole");
        assertEquals("{\"columns\":[],\"rows\":[],\"more\":true}",
                ActionResult.rows(null, List.of(), List.of(), true).body(), "more as given");
    }

    @Test
    void aValueThatIsNoJsonScalarIsWrittenAsItsText() {
        String body = ActionResult.rows("1 row", List.of(new Column("a", ""), new Column("b", ""),
                        new Column("c", ""), new Column("d", ""), new Column("e", "")),
                List.of(List.of(LocalDate.of(2026, 10, 1), Double.NaN, new BigDecimal("1E+3"), 'x', "a\nb\u0001")),
                false).body();

        assertTrue(body.contains("[[\"2026-10-01\",\"NaN\",1E+3,\"x\",\"a\\nb\\u0001\"]]"), body);
    }

    @Test
    void aRowOfAnotherWidthOrANameOrATypePast200CharactersIsRefused() {
        List<Column> two = List.of(new Column("a", ""), new Column("b", ""));

        IllegalArgumentException width = assertThrows(IllegalArgumentException.class,
                () -> ActionResult.rows("x", two, List.of(List.of(1, 2), List.of(3)), false));
        assertEquals("row 1 has 1 values for 2 columns", width.getMessage());
        assertEquals("y".repeat(200), new Column("y".repeat(200), "t").name());
        assertThrows(IllegalArgumentException.class, () -> new Column("y".repeat(201), "t"));
        assertThrows(IllegalArgumentException.class, () -> new Column("y", "t".repeat(201)));
        assertThrows(NullPointerException.class, () -> new Column(null, "t"));
    }

    @Test
    void rowsPastTheBodyLimitAreLeftOutAndSaySo() {
        List<List<Object>> many = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            many.add(List.of("v".repeat(100)));
        }

        String body = ActionResult.rows("3000 rows", List.of(new Column("text", "")), many, false).body();

        assertTrue(body.length() <= ActionResult.MAX_CONTENT, "never truncated: " + body.length());
        assertTrue(body.length() + ",[\"\"]".length() + 100 > ActionResult.MAX_CONTENT, "as many rows as fit");
        assertTrue(body.endsWith("\"]],\"more\":true}"), body.substring(body.length() - 40));
        assertFalse(body.contains(ActionResult.TRUNCATED), "whole rows only, no marker");
    }

    @Test
    void aBodyOrDetailsPastTheLimitIsTruncatedAndSaysSo() {
        String big = "a".repeat(300_000);

        ActionResult result = new ActionResult("ok", "text/plain", big, false, big);

        assertEquals(ActionResult.MAX_CONTENT, result.body().length());
        assertTrue(result.body().endsWith("… truncated at 256 KiB"), result.body().substring(262_100));
        assertEquals(ActionResult.MAX_CONTENT, result.details().length());
        String exact = "b".repeat(ActionResult.MAX_CONTENT);
        assertEquals(exact, new ActionResult("ok", "text/plain", exact, false, null).body());
    }

    @Test
    void theErrorFlagIsKept() {
        assertTrue(new ActionResult("error -32602: bad", "application/json", "{}", true, null).error());
        assertFalse(ActionResult.of("ok").error());
        assertNull(ActionResult.of("ok").body());
        assertNull(ActionResult.of("ok").details());
    }
}
