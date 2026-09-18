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
package io.vidocq.runtime.core.report;

import java.util.List;
import java.util.Objects;

/**
 * One section of the startup report, such as {@code layer}: text only, never a class, so that the
 * application layer of a dev reload stays collectable once its boot is over. What is printed is decided by
 * {@link StartupReportRenderer}, which also cleans and cuts every value.
 *
 * <pre>
 * layer         4 modules (boot-layer detection from com.acme.app)      id, headline
 *   com.acme.app  app/target/classes  directory                         a table row
 *   weaving     none                                                    a row
 * </pre>
 *
 * @param id       the name of the section, printed first: {@code layer}
 * @param headline what follows the id on the first line of the section in the detailed report, or
 *                 {@code null} for the id alone
 * @param summary  the value of the one line the section has in the summary report, or {@code null} when it has
 *                 none there
 * @param lines    the lines under the first one in the detailed report, in order
 * @param nanos    how long the section took to write, or {@code -1} when that was not measured
 */
public record Section(String id, String headline, String summary, List<Line> lines, long nanos) {

    public Section {
        Objects.requireNonNull(id, "id");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One line of a section in the detailed report. */
    public sealed interface Line permits Row, Items, Cells, Text {}

    /**
     * {@code key value}: the values of the rows and item lists of a section start in one column, two
     * spaces after its widest key and never before column 14; a long value wraps in that column.
     *
     * @param key   the name of the row
     * @param value the value
     */
    public record Row(String key, String value) implements Line {

        public Row {
            key = String.valueOf(key);
            value = String.valueOf(value);
        }
    }

    /**
     * {@code key item, item, ...}, aligned like a {@link Row}; at most
     * {@value StartupReportRenderer#MAX_ITEMS} items are printed, then how many more there are. An empty list
     * prints nothing.
     *
     * @param key   the name of the list
     * @param items the items
     */
    public record Items(String key, List<String> items) implements Line {

        public Items {
            key = String.valueOf(key);
            items = items == null ? List.of() : items.stream().map(String::valueOf).toList();
        }
    }

    /**
     * One row of a table: the consecutive table rows of a section share their column widths, two spaces
     * wider than the widest cell of each column.
     *
     * @param cells the cells, left to right
     */
    public record Cells(List<String> cells) implements Line {

        public Cells {
            cells = cells == null ? List.of() : cells.stream().map(String::valueOf).toList();
        }
    }

    /**
     * A line of text, indented like the others and printed at full width.
     *
     * @param text the text
     */
    public record Text(String text) implements Line {

        public Text {
            text = String.valueOf(text);
        }
    }
}
