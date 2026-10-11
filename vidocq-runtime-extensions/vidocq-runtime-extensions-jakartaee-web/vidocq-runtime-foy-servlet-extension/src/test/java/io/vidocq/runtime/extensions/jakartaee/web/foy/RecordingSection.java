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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records what a contributor writes into its section. */
final class RecordingSection implements StartupReportSection {

    String summary;
    final Map<String, String> rows = new LinkedHashMap<>();

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
        return this;
    }

    @Override
    public StartupReportSection secret(String key, boolean configured) {
        return this;
    }

    @Override
    public StartupReportSection listener(String name, String boundBaseUri) {
        return this;
    }

    @Override
    public StartupReportSection route(String listener, String method, String path, String handler) {
        return this;
    }

    @Override
    public StartupReportSection anomaly(String code, String message, String hint) {
        return this;
    }
}
