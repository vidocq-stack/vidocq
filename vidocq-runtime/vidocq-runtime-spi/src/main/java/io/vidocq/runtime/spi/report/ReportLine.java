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
package io.vidocq.runtime.spi.report;

import java.util.List;

/**
 * One line of a {@link ReportSection} in the detailed report. What it is follows from its key:
 *
 * <ul>
 *   <li>a row, {@code weaving none}: its key and its value, alone;</li>
 *   <li>a named list, {@code tools a, b, c}: its key and every item, where the report prints only the first
 *       ones;</li>
 *   <li>a row of a table: no key, and its cells from left to right;</li>
 *   <li>a line of text: no key, and the text alone.</li>
 * </ul>
 *
 * <p>A line may also point somewhere a reader can open: a {@link StartupReportSection#link link}, or a
 * {@link StartupReportSection#route route} a browser can request as it is. Its {@link #href()} is then that absolute
 * {@code http} or {@code https} URL, which the dev console shows as a link; the log prints the values alone.
 * @param key    the name of the row or the list, or {@code null} for a row of a table and a line of text
 * @param values the values, in order, a {@code null} one read as {@code "null"}, as the report prints it;
 *               never {@code null}, an immutable copy
 * @param href   where the line points: an absolute {@code http} or {@code https} URL, or {@code null}; any other
 *               value, a relative path or another scheme, is read as {@code null}
 */
public record ReportLine(String key, List<String> values, String href) {

    public ReportLine {
        values = values == null ? List.of() : values.stream().map(String::valueOf).toList();
        href = isWebUrl(href) ? href : null;
    }

    /**
     * A line that points nowhere.
     * @param key    the name of the row or the list, or {@code null}
     * @param values the values, in order
     */
    public ReportLine(String key, List<String> values) {
        this(key, values, null);
    }

    /** Whether {@code url} is an absolute {@code http} or {@code https} URL: nothing else may become a link. */
    private static boolean isWebUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"))
                && url.chars().noneMatch(c -> c <= ' ' || c == '"' || c == '<' || c == '>' || c == '\\');
    }
}
