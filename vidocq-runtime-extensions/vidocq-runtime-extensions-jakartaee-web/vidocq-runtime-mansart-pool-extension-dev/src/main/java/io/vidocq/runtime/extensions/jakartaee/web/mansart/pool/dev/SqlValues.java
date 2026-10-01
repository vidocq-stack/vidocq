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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A value of a row as the body of a result of rows holds it (SQL spec §4.2): a number as a number, a
 * {@link BigDecimal} or an integer past 2^53 as its text, so that JSON never rounds it; a boolean; a string, cut past
 * {@value #MAX_TEXT} characters; a date, a time or a timestamp as ISO text, with its offset when it has a zone; binary
 * as {@code 0x…} hex of its first {@value #MAX_BYTES} bytes, {@code …} after when there are more; an array as the
 * JSON of its elements; anything else, a value of a type the driver invents included, as its {@code toString()}.
 */
final class SqlValues {

    /** The largest integer a JSON number holds exactly: 2^53. */
    static final long MAX_EXACT = 1L << 53;
    /** How many bytes of a binary value are shown. */
    static final int MAX_BYTES = 64;
    /** How many characters of a text are kept: the page shows 200, and the rest in the cell's title. */
    static final int MAX_TEXT = 10_000;

    private SqlValues() {}

    /** The value of {@code column} of the current row, {@code type} and {@code typeName} its SQL type. */
    static Object of(ResultSet results, int column, int type, String typeName) throws SQLException {
        Object value = results.getObject(column);
        return switch (value) {
            case null -> null;
            case Boolean flag -> flag;
            case Integer number -> number;
            case Short number -> number;
            case Byte number -> number;
            case Long number -> number >= -MAX_EXACT && number <= MAX_EXACT ? number : number.toString();
            case BigInteger number -> number.bitLength() <= 53 ? number : number.toString();
            case BigDecimal number -> number.toPlainString();
            case Double number -> number;
            case Float number -> number;
            case String text -> cut(text);
            case Character c -> c.toString();
            case java.sql.Timestamp timestamp -> zoned(type, typeName) ? offset(results, column, timestamp)
                    : timestamp.toLocalDateTime().toString();
            case java.sql.Date date -> date.toLocalDate().toString();
            case java.sql.Time time -> time.toLocalTime().toString();
            case TemporalAccessor temporal -> temporal.toString();
            case byte[] bytes -> hex(bytes, bytes.length);
            case Blob blob -> hex(blob.getBytes(1, (int) Math.min(blob.length(), MAX_BYTES)), blob.length());
            case Clob clob -> clob.length() > MAX_TEXT ? clob.getSubString(1, MAX_TEXT) + "…"
                    : clob.getSubString(1, (int) clob.length());
            case SQLXML xml -> cut(xml.getString());
            case Array array -> elements(array);
            default -> cut(value.toString());
        };
    }

    /** {@code text}, cut past {@value #MAX_TEXT} characters with {@code …}. */
    private static String cut(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
    }

    /** Whether a timestamp column has a zone: its JDBC type says so, or its name does (PostgreSQL's timestamptz). */
    private static boolean zoned(int type, String typeName) {
        String name = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        return type == Types.TIMESTAMP_WITH_TIMEZONE || name.equals("TIMESTAMPTZ") || name.contains("WITH TIME ZONE");
    }

    /** A timestamp with a zone as ISO text with its offset, or as an instant when the driver gives no offset. */
    private static String offset(ResultSet results, int column, java.sql.Timestamp timestamp) {
        try {
            OffsetDateTime value = results.getObject(column, OffsetDateTime.class);
            return value == null ? timestamp.toInstant().toString() : value.toString();
        } catch (SQLException | RuntimeException noOffset) {
            return timestamp.toInstant().toString();
        }
    }

    /** {@code 0x} and the hex of {@code bytes}, at most {@value #MAX_BYTES} of {@code length}, {@code …} after. */
    private static String hex(byte[] bytes, long length) {
        int shown = Math.min(bytes.length, MAX_BYTES);
        return "0x" + HexFormat.of().formatHex(bytes, 0, shown) + (length > shown ? "…" : "");
    }

    /** The elements of an array as JSON text, each a number, a boolean, a string or {@code null}. */
    private static String elements(Array array) throws SQLException {
        Object raw = array.getArray();
        try {
            array.free();
        } catch (SQLException unsupported) {
            // a driver that keeps nothing to free
        }
        if (!(raw instanceof Object[] values)) {
            return cut(String.valueOf(raw));
        }
        List<Object> out = new ArrayList<>(values.length);
        for (Object element : values) {
            out.add(element == null || element instanceof Number || element instanceof Boolean
                    || element instanceof String ? element : element.toString());
        }
        return cut(Json.write(out));
    }
}
