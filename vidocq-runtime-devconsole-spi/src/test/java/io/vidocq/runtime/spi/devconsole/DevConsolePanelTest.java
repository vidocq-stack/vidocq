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
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What a panel gets without writing it: it is a report contributor, and it draws no chart unless it asks. */
class DevConsolePanelTest {

    /** The least a panel writes: an id, its section of the report, and its live values. */
    private static final class CachePanel implements DevConsolePanel {

        @Override
        public String id() {
            return "acme-cache";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("256 entries max");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.gauge("entries", 12, 256, Unit.COUNT).counter("hits", 3, Unit.COUNT);
        }
    }

    @Test
    void aPanelIsASectionOfTheStartupReportThatTheConsoleFindsAmongItsContributors() {
        List<StartupReportContributor> contributors = List.of(new CachePanel());

        DevConsolePanel panel = assertInstanceOf(DevConsolePanel.class, contributors.getFirst());
        assertEquals("acme-cache", panel.title(), "the title is the id unless the panel says otherwise");
        assertEquals(1000, panel.order());
    }

    @Test
    void aPanelDrawsNoChartUnlessItAsks() {
        List<Chart> charts = new CachePanel().charts();

        assertEquals(List.of(), charts);
        assertThrows(UnsupportedOperationException.class,
                () -> charts.add(new Chart("entries", "Entries", List.of(Series.area("entries")))));
    }

    @Test
    void aPanelOffersNoLanguageUnlessItAsks() {
        List<PanelLanguage> languages = new CachePanel().languages();

        assertEquals(List.of(), languages);
        assertThrows(UnsupportedOperationException.class, () -> languages.add(new PanelLanguage("jdql", "{}")));
    }
}
