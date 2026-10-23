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
package io.vidocq.bodoni.peano.internal;

import java.util.List;
import java.util.SortedMap;
import jakarta.servlet.jsp.jstl.sql.Result;
import io.vidocq.peano.spi.MethodDescriptor;

/**
 * Accessor table for {@link Result}, the {@code var} object of {@code sql:query} (Tags 3.0
 * section 10.3). Implementations are internal classes no table can name, so the table is keyed
 * on the interface and reached by Peano's supertype lookup. All properties are read-only: rows,
 * rowsByIndex, columnNames, rowCount and limitedByMaxRows. The matching getters (getRows,
 * getRowsByIndex, getColumnNames, getRowCount, isLimitedByMaxRows) are callable.
 */
final class ResultAccessorTable extends ReadOnlyAccessorTable {
    private static final List<String> PROPERTIES =
            List.of("rows", "rowsByIndex", "columnNames", "rowCount", "limitedByMaxRows");
    private static final MethodDescriptor[] METHODS = {
        descriptor("getRows", SortedMap[].class),
        descriptor("getRowsByIndex", Object[][].class),
        descriptor("getColumnNames", String[].class),
        descriptor("getRowCount", int.class),
        descriptor("isLimitedByMaxRows", boolean.class),
    };

    ResultAccessorTable() {
        super(Result.class, PROPERTIES, METHODS);
    }

    @Override
    protected Object read(Object base, int index) {
        var result = (Result) base;
        return switch (index) {
            case 0 -> result.getRows();
            case 1 -> result.getRowsByIndex();
            case 2 -> result.getColumnNames();
            case 3 -> result.getRowCount();
            default -> result.isLimitedByMaxRows();
        };
    }
}
