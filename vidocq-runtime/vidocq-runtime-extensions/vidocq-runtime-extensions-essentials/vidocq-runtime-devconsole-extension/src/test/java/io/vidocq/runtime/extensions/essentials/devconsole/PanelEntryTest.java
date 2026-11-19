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

import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A built-in panel whose boot facts or actions fail still gets its entry: the failure costs that part only. */
class PanelEntryTest {

    /** A panel whose boot facts and actions both fail. */
    private static final class FailingPanel implements DevConsolePanel {

        @Override
        public String id() {
            return "failing";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            throw new IllegalStateException("no boot facts");
        }

        @Override
        public List<PanelAction> actions() {
            throw new IllegalStateException("no actions");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.counter("calls", 1, Unit.COUNT);
        }
    }

    @Test
    void aPanelWhoseBootFactsAndActionsFailKeepsItsEntry() {
        FailingPanel panel = new FailingPanel();

        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);

        assertEquals("failing", entry.id());
        assertSame(panel, entry.panel());
        assertTrue(entry.section().lines().isEmpty(), "no boot facts");
        assertEquals(List.of(), entry.actions());
    }
}
