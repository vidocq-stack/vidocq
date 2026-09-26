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

import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePanelsTest {

    static class Live implements LivePanel {
        final String id;
        final List<String> calls = new ArrayList<>();

        Live(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start(io.vidocq.runtime.spi.ExtensionContext context) {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public void sample(PanelSample sample) {}
    }

    @Test
    void theFirstPanelOfAnIdWinsAndTheSecondIsReported() {
        Live first = new Live("rest");
        Live second = new Live("rest");
        List<String> warnings = new ArrayList<>();

        LivePanels panels = LivePanels.of(List.of(first, second), warnings::add);

        assertSame(first, panels.forSection("rest").orElseThrow());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("[" + LivePanels.DUPLICATE + "]"), warnings.getFirst());
    }

    @Test
    void startAndStopReachEveryPanelOncePerBoot() {
        Live rest = new Live("rest");
        LivePanels panels = LivePanels.of(List.of(rest), warning -> {});

        panels.startAll(null);
        panels.startAll(null);
        panels.stopAll();
        panels.stopAll();

        assertEquals(List.of("start", "stop"), rest.calls);
    }

    @Test
    void aPanelThatFailsToStartIsDroppedNotFatal() {
        LivePanel broken = new Live("rest") {
            @Override
            public void start(io.vidocq.runtime.spi.ExtensionContext context) {
                throw new IllegalStateException("boom");
            }
        };
        LivePanels panels = LivePanels.of(List.of(broken), warning -> {});

        panels.startAll(null);

        assertEquals(Optional.empty(), panels.forSection("rest"));
    }
}
