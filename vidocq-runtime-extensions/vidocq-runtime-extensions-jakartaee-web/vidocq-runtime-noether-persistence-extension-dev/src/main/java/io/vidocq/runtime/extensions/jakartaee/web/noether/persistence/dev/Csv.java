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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import java.util.List;

/** A table as CSV (RFC 4180, {@code \n} line ends): a header of the column names, then a line per row. */
final class Csv {

    /**
     * A table written as CSV.
     *
     * @param body the text
     * @param rows how many rows it holds
     */
    record Written(String body, int rows) {}

    private Csv() {
    }

    /** The header and the leading rows that keep the text within {@code max} characters. */
    static Written write(List<Column> columns, List<List<Object>> rows, int max) {
        StringBuilder out = new StringBuilder();
        line(columns.stream().map(Column::name).toList(), out);
        int written = 0;
        for (List<Object> row : rows) {
            int mark = out.length();
            line(row, out);
            if (out.length() > max) {
                out.setLength(mark);
                break;
            }
            written++;
        }
        return new Written(out.toString(), written);
    }

    private static void line(List<?> values, StringBuilder out) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Object value = values.get(i);
            if (value != null) {
                field(String.valueOf(value), out);
            }
        }
        out.append('\n');
    }

    private static void field(String text, StringBuilder out) {
        boolean quoted = text.chars().anyMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r');
        if (quoted) {
            out.append('"').append(text.replace("\"", "\"\"")).append('"');
        } else {
            out.append(text);
        }
    }
}
