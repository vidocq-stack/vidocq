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

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link SqlValues} reads of a value (SQL spec §4.2): a long text or a binary value through a stream, only as far
 * as it shows, never whole through {@code getObject}; an array of arrays written recursively.
 */
class SqlValuesTest {

    /** A {@link ResultSet} of one column whose {@code getObject} fails the test and whose streams never end. */
    private static ResultSet endless(AtomicLong consumed) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getObject", "getString", "getBytes", "getBlob", "getClob" ->
                            throw new AssertionError(method.getName() + " reads the whole value");
                    case "getCharacterStream" -> new Reader() {
                        @Override
                        public int read(char[] buffer, int offset, int length) {
                            java.util.Arrays.fill(buffer, offset, offset + length, 'a');
                            consumed.addAndGet(length);
                            return length;
                        }

                        @Override
                        public void close() {
                            // nothing to release
                        }
                    };
                    case "getBinaryStream" -> new InputStream() {
                        @Override
                        public int read() {
                            consumed.incrementAndGet();
                            return 1;
                        }

                        @Override
                        public int read(byte[] buffer, int offset, int length) {
                            java.util.Arrays.fill(buffer, offset, offset + length, (byte) 1);
                            consumed.addAndGet(length);
                            return length;
                        }
                    };
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aLongTextIsReadThroughItsStreamOnlyAsFarAsItIsKept() throws SQLException {
        AtomicLong consumed = new AtomicLong();

        for (int type : new int[] {Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB, Types.NVARCHAR}) {
            consumed.set(0);
            Object value = SqlValues.of(endless(consumed), 1, type, "text");

            assertEquals("a".repeat(SqlValues.MAX_TEXT) + "…", value, "type " + type);
            assertTrue(consumed.get() <= SqlValues.MAX_TEXT + 1, "read " + consumed.get() + " characters");
        }
    }

    @Test
    void aBinaryValueIsReadThroughItsStreamOnlyAsFarAsItIsShown() throws SQLException {
        AtomicLong consumed = new AtomicLong();

        for (int type : new int[] {Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB}) {
            consumed.set(0);
            Object value = SqlValues.of(endless(consumed), 1, type, "bytea");

            assertEquals("0x" + "01".repeat(SqlValues.MAX_BYTES) + "…", value, "type " + type);
            assertTrue(consumed.get() <= SqlValues.MAX_BYTES + 1, "read " + consumed.get() + " bytes");
        }
    }

    @Test
    void aShortTextAndAShortBinaryValueAreWholeAndANullStreamIsNull() throws SQLException {
        ResultSet results = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getCharacterStream" -> (Integer) arguments[0] == 1 ? new java.io.StringReader("hello")
                            : null;
                    case "getBinaryStream" -> (Integer) arguments[0] == 3
                            ? new java.io.ByteArrayInputStream(new byte[] {1, 2}) : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals("hello", SqlValues.of(results, 1, Types.VARCHAR, "text"));
        assertNull(SqlValues.of(results, 2, Types.VARCHAR, "text"));
        assertEquals("0x0102", SqlValues.of(results, 3, Types.VARBINARY, "bytea"));
        assertNull(SqlValues.of(results, 4, Types.VARBINARY, "bytea"));
    }

    private static ResultSet of(Object value) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getObject" -> value;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Array array(Object elements) {
        return (Array) Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[] {Array.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getArray" -> elements;
                    case "free" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void anArrayOfArraysIsWrittenRecursivelyNeverAsTheTextOfAnObjectArray() throws SQLException {
        Object nested = new Object[] {new Object[] {1, 2}, new Object[] {3, null}};
        Object primitive = new Object[] {new int[] {4, 5}, "x", array(new Object[] {true})};

        assertEquals("[[1,2],[3,null]]", SqlValues.of(of(array(nested)), 1, Types.ARRAY, "_int4"));
        assertEquals("[[4,5],\"x\",[true]]", SqlValues.of(of(array(primitive)), 1, Types.ARRAY, "_any"));
        assertEquals("[1,2]", SqlValues.of(of(array(new Object[] {1, 2})), 1, Types.ARRAY, "_int4"));
    }
}
