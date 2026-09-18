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

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;

import java.util.List;

/**
 * One panel of the console for one boot, as it is read once and shown on every poll: the boot facts of a section of
 * the report, and, for a {@link DevConsolePanel}, the panel to sample and its charts.
 *
 * @param id      the id of its contributor, the section's id
 * @param title   what the page calls it
 * @param section its boot facts
 * @param panel   what writes its live values, or {@code null} for a contributor that is no panel
 * @param charts  what the page plots of them; empty when there is no panel or its {@code charts()} failed
 */
record PanelEntry(String id, String title, ReportSection section, DevConsolePanel panel, List<Chart> charts) {

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    PanelEntry {
        charts = List.copyOf(charts);
    }

    /**
     * The panel of a contributor of the report, from the section it wrote: a {@link DevConsolePanel} is live, any
     * other contributor shows its boot facts only.
     *
     * @param contributor the contributor, as the report called it
     * @param section     the section it wrote
     */
    static PanelEntry contributed(StartupReportContributor contributor, ReportSection section) {
        DevConsolePanel panel = contributor instanceof DevConsolePanel live ? live : null;
        return new PanelEntry(section.id(), title(contributor, section.id()), section, panel,
                charts(panel, section.id()));
    }

    /**
     * One of the console's own panels: it writes its boot facts now, into a section of the console's own that never
     * logs, as a contributor writes its section of the report.
     *
     * @param panel the panel
     * @param mode  the launch mode of the boot
     */
    static PanelEntry builtIn(DevConsolePanel panel, LaunchMode mode) {
        String id = panel.id();
        String title = title(panel, id);
        RecordingSection section = new RecordingSection(id, title);
        try {
            panel.contribute(new ConsoleReportContext(mode), section);
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' failed to write its boot facts",
                    failed);
        }
        return new PanelEntry(id, title, section.toSection(), panel, charts(panel, id));
    }

    private static String title(StartupReportContributor contributor, String id) {
        try {
            String title = contributor.title();
            return title == null || title.isBlank() ? id : title;
        } catch (RuntimeException | LinkageError failed) {
            return id;
        }
    }

    /** The charts of {@code panel}, read once per boot; none when it has none or they fail. */
    private static List<Chart> charts(DevConsolePanel panel, String id) {
        if (panel == null) {
            return List.of();
        }
        try {
            List<Chart> charts = panel.charts();
            return charts == null ? List.of() : charts.stream().filter(chart -> chart != null).toList();
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no charts: its charts() failed",
                    failed);
            return List.of();
        }
    }
}
