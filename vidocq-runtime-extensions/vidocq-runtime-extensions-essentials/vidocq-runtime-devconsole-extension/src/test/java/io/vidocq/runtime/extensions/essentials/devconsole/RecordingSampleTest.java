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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a panel writes on a poll, as the snapshot carries it: every kind of value, the keys, the groups, the limits. */
class RecordingSampleTest {

    /** The sample as the snapshot writes it, read back. */
    private static Map<String, Object> written(RecordingSample sample) {
        JsonWriter out = new JsonWriter().beginObject();
        sample.writeTo(out);
        return Json.object(out.endObject().toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> values(Map<String, Object> scope) {
        return (List<Map<String, Object>>) scope.get("values");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> sample) {
        return (List<Map<String, Object>>) sample.get("groups");
    }

    @Test
    void everyKindOfValueHasItsShape() {
        RecordingSample sample = new RecordingSample();
        sample.gauge("threads", 12, Unit.COUNT)
                .gauge("heap.used", 1536, 4096, Unit.BYTES)
                .counter("borrows", 18_234, Unit.COUNT)
                .duration("mean-borrow", Duration.ofNanos(184_000))
                .text("state", "running")
                .absent("leaks", "leak detection off")
                .table("checks", List.of("name", "state"), List.of(List.of("db", "UP")));

        Map<String, Object> written = written(sample);

        assertEquals(false, written.get("truncated"));
        assertEquals(List.of(
                Map.of("key", "threads", "kind", "gauge", "value", 12L, "unit", "count"),
                Map.of("key", "heap.used", "kind", "gauge", "value", 1536L, "max", 4096L, "unit", "bytes"),
                Map.of("key", "borrows", "kind", "counter", "value", 18_234L, "unit", "count"),
                Map.of("key", "mean-borrow", "kind", "duration", "nanos", 184_000L),
                Map.of("key", "state", "kind", "text", "value", "running"),
                Map.of("key", "leaks", "kind", "absent", "reason", "leak detection off"),
                Map.of("key", "checks", "kind", "table", "columns", List.of("name", "state"),
                        "rows", List.of(List.of("db", "UP")))),
                values(written));
        assertEquals(List.of(), groups(written));
    }

    @Test
    void whatIsNoMeasureIsAbsentNeverZero() {
        RecordingSample sample = new RecordingSample();
        sample.gauge("nan", Double.NaN, Unit.RATIO)
                .gauge("infinite", Double.POSITIVE_INFINITY, 1, Unit.RATIO)
                .gauge("cpu", 0.5, Double.NaN, Unit.RATIO)
                .gauge("load", 0.5, 0, Unit.RATIO)
                .duration("none", null)
                .text("nothing", null)
                .absent("why", null);

        List<Map<String, Object>> values = values(written(sample));

        assertEquals(Map.of("key", "nan", "kind", "absent"), values.get(0));
        assertEquals(Map.of("key", "infinite", "kind", "absent"), values.get(1));
        assertEquals(Map.of("key", "cpu", "kind", "gauge", "value", 0.5, "unit", "ratio"), values.get(2),
                "a max that is no number is left out");
        assertEquals(Map.of("key", "load", "kind", "gauge", "value", 0.5, "unit", "ratio"), values.get(3),
                "a max that is not above zero is left out");
        assertEquals(Map.of("key", "none", "kind", "absent"), values.get(4));
        assertEquals(Map.of("key", "nothing", "kind", "absent"), values.get(5));
        assertEquals(Map.of("key", "why", "kind", "absent"), values.get(6));
    }

    @Test
    void theLastValueOfAKeyWinsInItsFirstPlace() {
        RecordingSample sample = new RecordingSample();
        sample.gauge("active", 1, Unit.COUNT).gauge("idle", 2, Unit.COUNT).gauge("active", 3, Unit.COUNT);

        List<Map<String, Object>> values = values(written(sample));

        assertEquals(List.of("active", "idle"), values.stream().map(v -> v.get("key")).toList());
        assertEquals(3L, values.get(0).get("value"));
    }

    @Test
    void aKeyThatBreaksTheRuleFailsTheSample() {
        RecordingSample sample = new RecordingSample();

        assertThrows(IllegalArgumentException.class, () -> sample.gauge("Heap", 1, Unit.BYTES));
        assertThrows(IllegalArgumentException.class, () -> sample.group("pool").counter("a b", 1, Unit.COUNT));
        assertThrows(NullPointerException.class, () -> sample.text(null, "x"));
        assertThrows(NullPointerException.class, () -> sample.gauge("heap", 1, null));
    }

    @Test
    void groupsKeepTheOrderOfTheirFirstCallAndDoNotNest() {
        RecordingSample sample = new RecordingSample();
        PanelSample audit = sample.group("audit");
        sample.group("@Default").gauge("active", 1, 8, Unit.COUNT);
        audit.gauge("active", 2, 4, Unit.COUNT);

        assertSame(audit, sample.group("audit"), "the same name, the same scope");
        assertThrows(IllegalStateException.class, () -> audit.group("nested"));
        assertThrows(IllegalArgumentException.class, () -> sample.group(" "));
        assertThrows(IllegalArgumentException.class, () -> sample.group(null));

        List<Map<String, Object>> groups = groups(written(sample));
        assertEquals(List.of("audit", "@Default"), groups.stream().map(g -> g.get("name")).toList());
        assertEquals(2L, values(groups.get(0)).get(0).get("value"));
    }

    @Test
    void stringsAreCleanedAndCut() {
        RecordingSample sample = new RecordingSample();
        sample.text("state", "line\nbreak\u001B[31m\u202Eflipped")
                .text("long", "x".repeat(500))
                .group("pool\nforged");

        Map<String, Object> written = written(sample);

        assertEquals("line?break?[31m?flipped", values(written).get(0).get("value"));
        String cut = (String) values(written).get(1).get("value");
        assertEquals(200, cut.length());
        assertTrue(cut.endsWith("..."), cut);
        assertEquals("pool?forged", groups(written).get(0).get("name"));
    }

    @Test
    void aTableIsPaddedCutAndLimited() {
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            columns.add("c" + i);
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("short", null));
        for (int i = 0; i < 150; i++) {
            rows.add(List.of("r" + i, "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));
        }
        RecordingSample sample = new RecordingSample();
        sample.table("wide", columns, rows);

        Map<String, Object> written = written(sample);
        @SuppressWarnings("unchecked")
        List<List<String>> table = (List<List<String>>) values(written).get(0).get("rows");

        assertEquals(true, written.get("truncated"));
        assertEquals(8, ((List<?>) values(written).get(0).get("columns")).size());
        assertEquals(100, table.size());
        assertEquals(List.of("short", "", "", "", "", "", "", ""), table.get(0));
        assertEquals(8, table.get(99).size());
    }

    @Test
    void aScopeHoldsSixtyFourValuesAndASampleSixtyFourGroups() {
        RecordingSample sample = new RecordingSample();
        for (int i = 0; i < 70; i++) {
            sample.counter("c" + i, i, Unit.COUNT);
            sample.group("g" + i).counter("c", i, Unit.COUNT);
        }
        sample.counter("c0", 99, Unit.COUNT);

        Map<String, Object> written = written(sample);

        assertEquals(true, written.get("truncated"));
        assertEquals(64, values(written).size());
        assertEquals(99L, values(written).get(0).get("value"), "a key already there is still written");
        assertEquals(64, groups(written).size());
        assertThrows(IllegalArgumentException.class, () -> sample.group("g69").counter("Bad", 1, Unit.COUNT),
                "a group beyond the limit still checks its keys");
    }

    @Test
    void aSampleWithinTheLimitsIsNotTruncated() {
        RecordingSample sample = new RecordingSample();
        sample.group("pool").gauge("active", 1, Unit.COUNT);

        assertEquals(false, written(sample).get("truncated"));
        assertNull(written(sample).get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReplayCellIsKeptWholeUpToItsLimitAndEmptiedPastIt() {
        RecordingSample sample = new RecordingSample();
        String replay = "tool.weather {\"arguments\":{\"city\":\"" + "a".repeat(300) + "\"}}";
        String tooLong = "tool.weather {\"arguments\":{\"city\":\"" + "a".repeat(PanelSample.MAX_REPLAY_CELL) + "\"}}";

        sample.table("calls", List.of("action", PanelSample.REPLAY_COLUMN),
                List.of(List.of("a".repeat(300), replay), List.of("b", tooLong)));

        List<List<String>> rows = (List<List<String>>) values(written(sample)).get(0).get("rows");
        assertEquals(200, rows.get(0).get(0).length(), "another column is cut as always");
        assertEquals(replay, rows.get(0).get(1));
        assertEquals("", rows.get(1).get(1), "a replay cell past its limit is emptied, never cut");
    }
}
