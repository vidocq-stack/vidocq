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

import java.util.Map;
import java.util.Set;

/**
 * The JSON Schema type of a Java type, by its name as a {@code UnitDescription} gives it: what a column of the page
 * and an attribute of the query editor's language say.
 */
final class JavaTypes {

    private static final Set<String> INTEGERS = Set.of("java.lang.Long", "long", "java.lang.Integer", "int",
            "java.lang.Short", "short", "java.lang.Byte", "byte", "java.math.BigInteger");
    private static final Set<String> NUMBERS = Set.of("java.lang.Double", "double", "java.lang.Float", "float",
            "java.math.BigDecimal");
    private static final Set<String> BOOLEANS = Set.of("java.lang.Boolean", "boolean");
    private static final String DATE_TIME = "date-time";
    private static final Map<String, String> FORMATS = Map.of(
            "java.time.LocalDate", "date",
            "java.sql.Date", "date",
            "java.time.LocalDateTime", DATE_TIME,
            "java.time.OffsetDateTime", DATE_TIME,
            "java.time.ZonedDateTime", DATE_TIME,
            "java.time.Instant", DATE_TIME,
            "java.sql.Timestamp", DATE_TIME,
            "java.time.LocalTime", "time",
            "java.sql.Time", "time",
            "java.util.UUID", "uuid");

    private JavaTypes() {
    }

    /** {@code integer}, {@code number}, {@code boolean} or, for anything else, {@code string}. */
    static String type(String javaType) {
        if (INTEGERS.contains(javaType)) {
            return "integer";
        }
        if (NUMBERS.contains(javaType)) {
            return "number";
        }
        return BOOLEANS.contains(javaType) ? "boolean" : "string";
    }

    /** {@code date}, {@code date-time}, {@code time} or {@code uuid}; {@code null} for another type. */
    static String format(String javaType) {
        return javaType == null ? null : FORMATS.get(javaType);
    }

    /** The type of a column: its format when it has one, its type otherwise. */
    static String column(String javaType) {
        String format = format(javaType);
        return format != null ? format : type(javaType);
    }
}
