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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link StartupReportSection} that keeps what it is given, for assertions. */
final class RecordingSection implements StartupReportSection {

    /** One anomaly as the section received it. */
    record Anomaly(String code, String message, String hint) {}

    /** One route as the section received it. */
    record Route(String listener, String method, String path, String handler) {}

    String summary;
    final Map<String, String> rows = new LinkedHashMap<>();
    final Map<String, List<String>> lists = new LinkedHashMap<>();
    final Map<String, Boolean> secrets = new LinkedHashMap<>();
    final List<Anomaly> anomalies = new ArrayList<>();
    final List<Route> routes = new ArrayList<>();

    @Override
    public StartupReportSection summary(String text) {
        summary = text;
        return this;
    }

    @Override
    public StartupReportSection row(String key, Object value) {
        rows.put(key, String.valueOf(value));
        return this;
    }

    @Override
    public StartupReportSection list(String key, Collection<String> items) {
        lists.put(key, List.copyOf(items));
        return this;
    }

    @Override
    public StartupReportSection secret(String key, boolean configured) {
        secrets.put(key, configured);
        return this;
    }

    @Override
    public StartupReportSection listener(String name, String boundBaseUri) {
        throw new AssertionError("the rest section starts no listener: Chappe does");
    }

    @Override
    public StartupReportSection route(String listener, String method, String path, String handler) {
        routes.add(new Route(listener, method, path, handler));
        return this;
    }

    @Override
    public StartupReportSection anomaly(String code, String message, String hint) {
        anomalies.add(new Anomaly(code, message, hint));
        return this;
    }

    /** The codes of the anomalies, in order. */
    List<String> codes() {
        return anomalies.stream().map(Anomaly::code).toList();
    }

    /** Everything written, as one string: what a secret must never appear in. */
    String everything() {
        return summary + rows + lists + secrets + anomalies + routes;
    }
}
