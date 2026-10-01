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
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The body of an {@link ActionResult#ROWS} result, written by hand: this module depends on no JSON library. It stays
 * within {@link ActionResult#MAX_CONTENT} characters: the rows that would pass it are left out and {@code more} set,
 * so that the body is never truncated in the middle of its JSON.
 */
final class RowsBody {

    /** A number as JSON writes it: anything else a {@link Number} prints, such as {@code NaN}, is written as text. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    /** The end of a body whose rows are not all there, the longer of the two ends. */
    private static final String MORE = "],\"more\":true}";
    /** The end of a body that holds every row. */
    private static final String ALL = "],\"more\":false}";

    private RowsBody() {}

    static String write(List<Column> columns, List<? extends List<?>> rows, boolean more) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        int width = columns.size();
        for (int r = 0; r < rows.size(); r++) {
            int values = Objects.requireNonNull(rows.get(r), "row").size();
            if (values != width) {
                throw new IllegalArgumentException("row " + r + " has " + values + " values for " + width
                        + " columns");
            }
        }
        StringBuilder out = new StringBuilder("{\"columns\":[");
        for (int i = 0; i < width; i++) {
            Column column = Objects.requireNonNull(columns.get(i), "column");
            out.append(i == 0 ? "{\"name\":" : ",{\"name\":");
            quote(column.name(), out);
            out.append(",\"type\":");
            quote(column.type(), out);
            out.append('}');
        }
        out.append("],\"rows\":[");
        if (out.length() + MORE.length() > ActionResult.MAX_CONTENT) {
            throw new IllegalArgumentException("the columns of a result pass " + ActionResult.MAX_CONTENT
                    + " characters");
        }
        boolean cut = false;
        StringBuilder row = new StringBuilder();
        for (int r = 0; r < rows.size() && !cut; r++) {
            row.setLength(0);
            row.append(r == 0 ? "[" : ",[");
            List<?> values = rows.get(r);
            for (int i = 0; i < width; i++) {
                if (i > 0) {
                    row.append(',');
                }
                value(values.get(i), row);
            }
            row.append(']');
            cut = out.length() + row.length() + MORE.length() > ActionResult.MAX_CONTENT;
            if (!cut) {
                out.append(row);
            }
        }
        return out.append(cut || more ? MORE : ALL).toString();
    }

    private static void value(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Boolean flag -> out.append(flag);
            case Number number when NUMBER.matcher(number.toString()).matches() -> out.append(number);
            default -> quote(String.valueOf(value), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
