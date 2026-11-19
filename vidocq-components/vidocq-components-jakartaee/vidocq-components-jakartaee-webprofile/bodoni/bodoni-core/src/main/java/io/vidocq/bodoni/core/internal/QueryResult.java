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

package io.vidocq.bodoni.core.internal;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import jakarta.servlet.jsp.jstl.sql.Result;

/**
 * A disconnected {@link Result}: the rows are read once from a {@link ResultSet}, which the
 * caller stays free to close, and are never touched again. It is built here rather than by the
 * API's own helper because {@code startRow} has to be honoured and the way that helper moves
 * the cursor is not documented.
 *
 * <p>Column names are the JDBC labels ({@code AS} aliases win). Each row map is unmodifiable and
 * case-insensitive; a label that occurs twice keeps the first column in the map, while the
 * indexed view holds every column. Every getter returns a fresh copy.
 */
public final class QueryResult implements Result {

    private final String[] columnNames;
    private final List<SortedMap<String, Object>> rows;
    private final List<Object[]> rowsByIndex;
    private final boolean limited;

    private QueryResult(String[] columnNames, List<SortedMap<String, Object>> rows,
            List<Object[]> rowsByIndex, boolean limited) {
        this.columnNames = columnNames;
        this.rows = rows;
        this.rowsByIndex = rowsByIndex;
        this.limited = limited;
    }

    /**
     * Reads {@code rs} from its current position.
     *
     * @param rs       the result set, not closed by this method
     * @param startRow the number of rows to skip, {@code >= 0}
     * @param maxRows  the number of rows to keep, {@code -1} for all
     * @return the disconnected result; limited when {@code maxRows != -1} and a further row existed
     * @throws SQLException if reading the result set fails
     */
    public static QueryResult of(ResultSet rs, int startRow, int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = meta.getColumnLabel(i + 1);
        }
        for (int skipped = 0; skipped < startRow; skipped++) {
            if (!rs.next()) {
                return new QueryResult(names, List.of(), List.of(), false);
            }
        }
        List<SortedMap<String, Object>> rows = new ArrayList<>();
        List<Object[]> byIndex = new ArrayList<>();
        boolean limited = false;
        while (rs.next()) {
            if (maxRows != -1 && rows.size() >= maxRows) {
                limited = true;
                break;
            }
            Object[] values = new Object[count];
            TreeMap<String, Object> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < count; i++) {
                values[i] = rs.getObject(i + 1);
                map.putIfAbsent(names[i], values[i]);
            }
            rows.add(Collections.unmodifiableSortedMap(map));
            byIndex.add(values);
        }
        return new QueryResult(names, rows, byIndex, limited);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SortedMap<String, Object>[] getRows() {
        return rows.toArray(new SortedMap[0]);
    }

    @Override
    public Object[][] getRowsByIndex() {
        Object[][] copy = new Object[rowsByIndex.size()][];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = rowsByIndex.get(i).clone();
        }
        return copy;
    }

    @Override
    public String[] getColumnNames() {
        return columnNames.clone();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public boolean isLimitedByMaxRows() {
        return limited;
    }
}
