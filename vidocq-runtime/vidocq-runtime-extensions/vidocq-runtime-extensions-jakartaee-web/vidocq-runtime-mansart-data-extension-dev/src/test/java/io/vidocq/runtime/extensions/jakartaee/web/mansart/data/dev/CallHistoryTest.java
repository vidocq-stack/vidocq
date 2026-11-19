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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The last 20 calls of each repository, one table whose replay column the page splits by tab (spec §8). */
class CallHistoryTest {

    private final CallHistory history = new CallHistory();

    private Table table() {
        RecordedSample sample = new RecordedSample();
        history.writeTo(sample);
        return (Table) sample.value("calls");
    }

    @Test
    void theLastTwentyOfEachRepositoryNewestFirst() {
        for (int i = 1; i <= 25; i++) {
            history.add("GizmoRepository", 0L, "find" + i, "1 row", i, "{}", "m.g.find {}");
        }

        List<CallHistory.Call> calls = history.calls("GizmoRepository");

        assertEquals(20, calls.size());
        assertEquals("find25", calls.get(0).method());
        assertEquals("find6", calls.get(19).method());
    }

    @Test
    void theTableMergesTheRepositoriesNewestFirst() {
        history.add("A", 0L, "one", "1 row", 1, "{}", "m.a.one {\"arguments\":{}}");
        history.add("B", 0L, "two", "no row", 2, "{}", "m.b.two {\"arguments\":{}}");
        history.add("A", 0L, "three", "done", 3, "{}", "m.a.three {\"arguments\":{}}");

        Table table = table();

        assertEquals(List.of("time", "method", "outcome", "ms", "arguments", "replay"), table.columns());
        assertEquals(List.of("three", "two", "one"), table.rows().stream().map(row -> row.get(1)).toList());
        assertEquals(List.of("done", "3", "{}", "m.a.three {\"arguments\":{}}"),
                table.rows().get(0).subList(2, 6));
        assertTrue(table.rows().get(0).get(0).matches("\\d{2}:\\d{2}:\\d{2}"), table.rows().get(0).get(0));
    }

    @Test
    void longArgumentsAreCutAndALongReplayIsDropped() {
        String arguments = "{\"pattern\":\"" + "x".repeat(300) + "\"}";
        String replay = "m.g.search {\"arguments\":{\"pattern\":\"" + "y".repeat(5000) + "\"}}";

        history.add("A", 0L, "search", "no row", 1, arguments, replay);

        CallHistory.Call call = history.calls("A").getFirst();
        assertEquals(201, call.arguments().length());
        assertTrue(call.arguments().endsWith("…"));
        assertEquals("", call.replay());
    }

    @Test
    void atMostOneHundredRows() {
        for (int group = 0; group < 6; group++) {
            for (int i = 0; i < 20; i++) {
                history.add("G" + group, 0L, "m", "done", 0, "{}", "");
            }
        }

        assertEquals(100, table().rows().size());
    }
}
