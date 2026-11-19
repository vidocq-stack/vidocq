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

import io.vidocq.noether.jpa.core.spi.UnitDescription.Table;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.IndexDiff.Found;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The indexes the database has on a unit's tables, read through {@link DatabaseMetaData#getIndexInfo}. A table's name
 * is looked up as the database stores an unquoted one, upper or lower case, and as written when quoted.
 */
final class DatabaseIndexes {

    /** The columns of one index, by their position in it. */
    private record Building(Map<Short, String> columns, boolean unique) {}

    private DatabaseIndexes() {
    }

    /** The indexes of {@code tables}, each named by its table as the unit names it. */
    static List<Found> read(Connection connection, List<Table> tables) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<Found> found = new ArrayList<>();
        for (Table table : tables) {
            int dot = table.name().lastIndexOf('.');
            String schema = dot < 0 ? null : stored(meta, table.name().substring(0, dot));
            String name = stored(meta, table.name().substring(dot + 1));
            found.addAll(indexes(meta, schema, name, table.name()));
        }
        return found;
    }

    private static List<Found> indexes(DatabaseMetaData meta, String schema, String name, String shown)
            throws SQLException {
        Map<String, Building> byName = new LinkedHashMap<>();
        try (ResultSet rows = meta.getIndexInfo(null, schema, name, false, false)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                boolean statistic = rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic;
                if (!statistic && index != null && column != null) {
                    boolean unique = !rows.getBoolean("NON_UNIQUE");
                    byName.computeIfAbsent(index, key -> new Building(new TreeMap<>(), unique)).columns()
                            .put(rows.getShort("ORDINAL_POSITION"), column);
                }
            }
        }
        List<Found> found = new ArrayList<>();
        byName.forEach((index, building) -> found.add(new Found(shown, index,
                List.copyOf(building.columns().values()), building.unique())));
        return found;
    }

    /** {@code identifier} as the database stores it: unquoted, or in its case when it was written without quotes. */
    private static String stored(DatabaseMetaData meta, String identifier) throws SQLException {
        String name = identifier.strip();
        if (name.length() > 1 && "\"`".indexOf(name.charAt(0)) >= 0 && name.charAt(name.length() - 1) == name.charAt(0)) {
            return name.substring(1, name.length() - 1);
        }
        if (meta.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        return meta.storesLowerCaseIdentifiers() ? name.toLowerCase(Locale.ROOT) : name;
    }
}
