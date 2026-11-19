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

import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The one choice a panel may offer above its sub-tabs: which argument it fills, and what the page calls it. */
class PanelSelectorTest {

    @Test
    void aSelectorNamesAnArgumentAndALabel() {
        PanelSelector selector = new PanelSelector("unit", "Persistence unit");

        assertEquals("unit", selector.argument());
        assertEquals("Persistence unit", selector.label());
    }

    @Test
    void anArgumentBreakingTheKeyRuleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PanelSelector("Unit", "Persistence unit"));
        assertThrows(NullPointerException.class, () -> new PanelSelector(null, "Persistence unit"));
    }

    @Test
    void aBlankLabelIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PanelSelector("unit", " "));
        assertThrows(NullPointerException.class, () -> new PanelSelector("unit", null));
    }

    @Test
    void aPanelHasNoSelectorByDefault() {
        LivePanel live = new LivePanel() {
            @Override
            public String id() {
                return "acme";
            }

            @Override
            public void sample(PanelSample sample) { /* nothing to show */ }
        };
        DevConsolePanel panel = new DevConsolePanel() {
            @Override
            public String id() {
                return "acme";
            }

            @Override
            public void contribute(StartupReportContext context, StartupReportSection section) {
                section.summary("idle");
            }

            @Override
            public void sample(PanelSample sample) { /* nothing to show */ }
        };

        assertEquals(Optional.empty(), live.selector());
        assertEquals(Optional.empty(), panel.selector());
    }
}
