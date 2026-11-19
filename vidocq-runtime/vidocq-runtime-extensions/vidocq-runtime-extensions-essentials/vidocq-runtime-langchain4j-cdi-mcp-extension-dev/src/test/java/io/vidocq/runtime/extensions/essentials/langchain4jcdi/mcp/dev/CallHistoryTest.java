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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §3.4: the last 20 calls of the boot, newest first. */
class CallHistoryTest {

    private static CallHistory.Call call(int n) {
        return new CallHistory.Call(1_789_740_602_114L + n, "tool.t", "T", "{\"n\":" + n + "}", "ok in 1 ms",
                false, 1, "{}", "tool.t {\"arguments\":{\"n\":" + n + "}}");
    }

    @Test
    void itKeepsTheLastTwentyNewestFirst() {
        CallHistory history = new CallHistory();

        for (int n = 1; n <= 25; n++) {
            history.add(call(n));
        }

        assertEquals(20, history.calls().size());
        assertEquals("{\"n\":25}", history.calls().getFirst().arguments());
        assertEquals("{\"n\":6}", history.calls().getLast().arguments());
    }

    @Test
    void itIsATableWithAReplayColumn() {
        assertEquals(List.of("time", "action", "outcome", "result", "ms", "arguments", PanelSample.REPLAY_COLUMN),
                CallHistory.COLUMNS);
        RecordingSample sample = new RecordingSample();
        CallHistory history = new CallHistory();
        history.add(call(1));

        history.writeTo(sample);

        assertEquals("table", sample.kind("calls"));
        assertTrue(sample.text("calls").contains("tool.t {\"arguments\":{\"n\":1}}"), sample.text("calls"));
    }
}
