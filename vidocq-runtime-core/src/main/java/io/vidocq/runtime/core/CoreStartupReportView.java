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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.report.Anomaly;
import io.vidocq.runtime.core.report.Phase;
import io.vidocq.runtime.core.report.Section;
import io.vidocq.runtime.core.report.StartupReport;
import io.vidocq.runtime.core.report.StartupReportRenderer;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportView;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The read-only view of the startup report of one boot, which the extensions read through
 * {@link io.vidocq.runtime.spi.ExtensionContext#startupReport()}: the text of the {@link StartupReport}, copied
 * when the report is written, and the contributors that wrote its sections. Nothing in it reaches back into the
 * recorder, so no reader can add an anomaly or a section.
 *
 * <p>The header of the detailed report comes first, as sections of its own: {@code launch} (the mode, the reason
 * and the level the report was logged at), {@code vidocq} (the runtime, when the boot read it) and
 * {@code phases} (one row per phase). The core's sections and the contributed ones follow, as the report has
 * them, a headline without the time its contributor took to write it. Values are the text the application and
 * its libraries wrote, uncleaned and uncut, and a list keeps every item: a reader escapes them for where it shows
 * them. The anomalies are cleaned already, like every anomaly the recorder logs.
 *
 * <p>Immutable, and read from any thread; its {@link #detailedText()} is rendered on the first call, once.
 */
final class CoreStartupReportView implements StartupReportView {

    private final StartupReport report;
    private final List<ReportAnomaly> anomalies;
    private final List<ReportSection> sections;
    private final List<StartupReportContributor> contributors;
    private volatile String detailedText;

    /**
     * @param report       the report of the boot
     * @param contributors the contributors whose sections it has, in the order of their sections
     */
    CoreStartupReportView(StartupReport report, List<StartupReportContributor> contributors) {
        this.report = report;
        this.anomalies = anomalies(report.anomalies());
        this.sections = sections(report);
        this.contributors = List.copyOf(contributors);
    }

    @Override
    public LaunchMode launchMode() {
        return report.launchMode();
    }

    @Override
    public String launchReason() {
        return report.launchReason();
    }

    @Override
    public List<ReportAnomaly> anomalies() {
        return anomalies;
    }

    @Override
    public List<ReportSection> sections() {
        return sections;
    }

    /** The report rendered by {@link StartupReportRenderer} from a copy at {@link Verbosity#DETAILED}. */
    @Override
    public String detailedText() {
        String text = detailedText;
        if (text == null) {
            synchronized (this) {
                text = detailedText;
                if (text == null) {
                    text = StartupReportRenderer.render(detailed(report));
                    detailedText = text;
                }
            }
        }
        return text;
    }

    @Override
    public List<StartupReportContributor> contributors() {
        return contributors;
    }

    /** {@code report} at {@link Verbosity#DETAILED}, whatever level it was logged at. */
    private static StartupReport detailed(StartupReport report) {
        return new StartupReport(report.launchMode(), report.launchReason(), Verbosity.DETAILED, report.phases(),
                report.sections(), report.anomalies(), report.failedPhase(), report.runtime());
    }

    /** The header's lines as sections, then the sections of {@code report}. */
    private static List<ReportSection> sections(StartupReport report) {
        List<ReportSection> sections = new ArrayList<>();
        String mode = report.launchMode().label();
        String launch = report.launchReason() == null ? mode : mode + " (" + report.launchReason() + ")";
        List<ReportLine> launchLines = new ArrayList<>();
        launchLines.add(row("mode", mode));
        if (report.launchReason() != null) {
            launchLines.add(row("reason", report.launchReason()));
        }
        launchLines.add(row("report", report.verbosity().name().toLowerCase(Locale.ROOT)));
        sections.add(new ReportSection("launch", launch, launch, launchLines));
        if (report.runtime() != null) {
            sections.add(new ReportSection("vidocq", report.runtime(), null, List.of()));
        }
        if (!report.phases().isEmpty()) {
            List<String> phases = new ArrayList<>();
            List<ReportLine> phaseLines = new ArrayList<>();
            for (Phase phase : report.phases()) {
                phases.add(phase.name() + " " + phase.millis() + " ms");
                phaseLines.add(row(phase.name(), phase.millis() + " ms"));
            }
            sections.add(new ReportSection("phases", String.join(" | ", phases), null, phaseLines));
        }
        for (Section section : report.sections()) {
            sections.add(new ReportSection(section.id(), section.headline(), section.summary(), lines(section)));
        }
        return List.copyOf(sections);
    }

    /** The lines of {@code section}; a list with no item, which the report does not print, is left out too. */
    private static List<ReportLine> lines(Section section) {
        List<ReportLine> lines = new ArrayList<>();
        for (Section.Line line : section.lines()) {
            switch (line) {
                case Section.Row row -> lines.add(new ReportLine(row.key(), List.of(row.value()), row.href()));
                case Section.Items items -> {
                    if (!items.items().isEmpty()) {
                        lines.add(new ReportLine(items.key(), items.items()));
                    }
                }
                case Section.Cells cells -> lines.add(new ReportLine(null, cells.cells(), cells.href()));
                case Section.Text text -> lines.add(new ReportLine(null, List.of(text.text())));
            }
        }
        return lines;
    }

    private static ReportLine row(String key, String value) {
        return new ReportLine(key, List.of(value));
    }

    private static List<ReportAnomaly> anomalies(List<Anomaly> anomalies) {
        return anomalies.stream()
                .map(anomaly -> new ReportAnomaly(anomaly.code(), anomaly.message(), anomaly.hint(), anomaly.source()))
                .toList();
    }
}
