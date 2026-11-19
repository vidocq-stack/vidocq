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

import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link StartupReportSection} kept in memory, never logged: where the console has its own panels write their
 * boot facts, once per boot, as a contributor writes its section of the report. It becomes a {@link ReportSection},
 * the shape the report's own sections have for the console.
 *
 * <p>The lines keep the order they were written in: a row, a list or a secret is a line with its key; a route is a
 * line of cells, its method, path and handler; a listener is a row. An anomaly is kept aside, with this section's id
 * as its source. Every method accepts {@code null} and never throws. Written on one thread, then read.
 */
final class RecordingSection implements StartupReportSection {

    private final String id;
    private final String title;
    private final List<ReportLine> lines = new ArrayList<>();
    private final List<ReportAnomaly> anomalies = new ArrayList<>();
    private String summary;

    /**
     * @param id    the id of the contributor, the source of its anomalies
     * @param title its title, the headline of the section
     */
    RecordingSection(String id, String title) {
        this.id = id;
        this.title = title;
    }

    @Override
    public StartupReportSection summary(String text) {
        summary = text;
        return this;
    }

    @Override
    public StartupReportSection row(String key, Object value) {
        lines.add(new ReportLine(String.valueOf(key), List.of(String.valueOf(value))));
        return this;
    }

    @Override
    public StartupReportSection list(String key, Collection<String> items) {
        if (items != null && !items.isEmpty()) {
            lines.add(new ReportLine(String.valueOf(key), new ArrayList<>(items)));
        }
        return this;
    }

    @Override
    public StartupReportSection secret(String key, boolean configured) {
        return row(key, configured ? "configured" : "not configured");
    }

    @Override
    public StartupReportSection listener(String name, String boundBaseUri) {
        return row(name, boundBaseUri);
    }

    @Override
    public StartupReportSection route(String listener, String method, String path, String handler) {
        lines.add(new ReportLine(null, List.of(String.valueOf(method), String.valueOf(path),
                String.valueOf(handler))));
        return this;
    }

    @Override
    public StartupReportSection anomaly(String code, String message, String hint) {
        anomalies.add(new ReportAnomaly(String.valueOf(code), String.valueOf(message), hint, id));
        return this;
    }

    /** The section written: its id, the title as its headline, its summary and its lines, in order. */
    ReportSection toSection() {
        return new ReportSection(id, title, summary, lines);
    }

    /** The anomalies written, in order. */
    List<ReportAnomaly> anomalies() {
        return List.copyOf(anomalies);
    }
}
