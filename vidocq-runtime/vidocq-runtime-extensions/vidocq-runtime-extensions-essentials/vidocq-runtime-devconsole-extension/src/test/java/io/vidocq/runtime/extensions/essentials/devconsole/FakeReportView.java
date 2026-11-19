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

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportView;

import java.util.ArrayList;
import java.util.List;

/**
 * A startup report as the core publishes it: the header and core sections first, then one section per contributor,
 * each written by that contributor into a {@link RecordingSection} as the core's report would have it.
 */
record FakeReportView(LaunchMode launchMode, String launchReason, List<ReportAnomaly> anomalies,
                      List<ReportSection> sections, String detailedText,
                      List<StartupReportContributor> contributors) implements StartupReportView {

    /** The report of a dev boot whose contributors are {@code contributors}, called in that order. */
    static FakeReportView of(StartupReportContributor... contributors) {
        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("launch", "dev (vidocq.launch.mode)", "dev (vidocq.launch.mode)",
                List.of(new ReportLine("mode", List.of("dev")))));
        sections.add(new ReportSection("layer", "2 modules", "2 modules",
                List.of(new ReportLine(null, List.of("com.acme.app", "target/classes", "directory")),
                        new ReportLine("weaving", List.of("none")))));
        List<ReportAnomaly> anomalies = new ArrayList<>();
        anomalies.add(new ReportAnomaly("VIDOCQ-CFG-003", "vidocq.pool.urll is read by nothing", "Remove it",
                "core"));
        for (StartupReportContributor contributor : contributors) {
            RecordingSection section = new RecordingSection(contributor.id(), contributor.title());
            contributor.contribute(new ConsoleReportContext(LaunchMode.DEV), section);
            ReportSection written = section.toSection();
            // the core's view has the summary line first among the lines, as the detailed report prints it
            List<ReportLine> lines = new ArrayList<>();
            if (written.summary() != null) {
                lines.add(new ReportLine(null, List.of(written.summary())));
            }
            lines.addAll(written.lines());
            sections.add(new ReportSection(written.id(), written.headline(), written.summary(), lines));
            anomalies.addAll(section.anomalies());
        }
        return new FakeReportView(LaunchMode.DEV, "vidocq.launch.mode", List.copyOf(anomalies),
                List.copyOf(sections), "Vidocq startup report\n  launch      dev (vidocq.launch.mode)",
                List.of(contributors));
    }
}
