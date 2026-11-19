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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A {@link StartupReportSection} that keeps what a contributor writes, as the report and the dev console both receive
 * it: the summary, the rows in order, and the anomalies.
 */
final class RecordedSection implements StartupReportSection {

    /** A row or a list: its key, then its values. */
    record Row(String key, List<String> values) {}

    /** An anomaly, as {@link StartupReportSection#anomaly} received it. */
    record Anomaly(String code, String message, String hint) {}

    private final List<Row> rows = new ArrayList<>();
    private final List<Anomaly> anomalies = new ArrayList<>();
    private String summary;

    @Override
    public StartupReportSection summary(String text) {
        summary = text;
        return this;
    }

    @Override
    public StartupReportSection row(String key, Object value) {
        rows.add(new Row(Objects.requireNonNull(key, "key"), List.of(String.valueOf(value))));
        return this;
    }

    @Override
    public StartupReportSection list(String key, Collection<String> items) {
        if (items != null && !items.isEmpty()) {
            rows.add(new Row(Objects.requireNonNull(key, "key"), List.copyOf(items)));
        }
        return this;
    }

    @Override
    public StartupReportSection secret(String key, boolean configured) {
        return row(key, configured ? "configured" : "not configured");
    }

    @Override
    public StartupReportSection listener(String name, String boundBaseUri) {
        throw new UnsupportedOperationException("Mansart Data declares no listener");
    }

    @Override
    public StartupReportSection route(String listener, String method, String path, String handler) {
        throw new UnsupportedOperationException("Mansart Data serves no route");
    }

    @Override
    public StartupReportSection anomaly(String code, String message, String hint) {
        anomalies.add(new Anomaly(Objects.requireNonNull(code, "code"), Objects.requireNonNull(message, "message"),
                hint));
        return this;
    }

    /** The summary line, or {@code null} when none was written. */
    String summary() {
        return summary;
    }

    /** The keys of the rows and lists, in order. */
    List<String> keys() {
        return rows.stream().map(Row::key).toList();
    }

    /** The values of the row {@code key}, joined with a comma, or {@code null} when there is no such row. */
    String value(String key) {
        return rows.stream().filter(r -> r.key().equals(key)).findFirst()
                .map(r -> String.join(", ", r.values())).orElse(null);
    }

    /** The anomalies, in order. */
    List<Anomaly> anomalies() {
        return List.copyOf(anomalies);
    }
}
