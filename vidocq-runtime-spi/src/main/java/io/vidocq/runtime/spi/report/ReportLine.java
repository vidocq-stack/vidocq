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
 * @param key    the name of the row or the list, or {@code null} for a row of a table and a line of text
 * @param values the values, in order, a {@code null} one read as {@code "null"}, as the report prints it;
 *               never {@code null}, an immutable copy
 */
public record ReportLine(String key, List<String> values) {

    public ReportLine {
        values = values == null ? List.of() : values.stream().map(String::valueOf).toList();
    }
}
