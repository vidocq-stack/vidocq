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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The CSV of the <i>JDQL</i> tab (CSV spec §5), RFC 4180, written and read here rather than by a library. Fields are
 * separated by the chosen separator, records ended by {@value #LINE_END} when written; {@code \r\n}, {@code \n} and a
 * last record without an end of line are read, and a UTF-8 byte order mark at the start is ignored. A field holding
 * the separator, a {@code "}, a {@code \r} or a {@code \n}, or holding nothing, is quoted, a {@code "} doubled.
 * {@code null} is an empty unquoted field and the empty text {@code ""}: both are read back as written.
 */
final class Csv {

    /** What ends each record written. */
    static final String LINE_END = "\r\n";

    /**
     * One record read.
     *
     * @param line   the line of the text it starts on, the first being 1; line ends inside quoted fields counted
     * @param fields its fields, {@code null} for an empty unquoted one
     */
    record Record(int line, List<String> fields) {}

    /** A text that is not CSV: {@code line <n>: <why>}. */
    static final class Malformed extends Exception {

        private static final long serialVersionUID = 1L;

        Malformed(int line, String why) {
            super("line " + line + ": " + why, null, false, false);
        }
    }

    private Csv() {}

    /** {@code value} as one field: quoted when needed; {@code null} as nothing, the empty text as {@code ""}. */
    static String field(String value, char separator) {
        if (value == null) {
            return "";
        }
        boolean quote = value.isEmpty();
        for (int i = 0; i < value.length() && !quote; i++) {
            char c = value.charAt(i);
            quote = c == separator || c == '"' || c == '\r' || c == '\n';
        }
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /** How many bytes {@code text} takes in UTF-8; a lone surrogate counts three. */
    static long utf8Length(CharSequence text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * The records of {@code text}. An empty line is a record of one {@code null} field; a lone {@code \r} and a
     * {@code "} inside an unquoted field are text.
     *
     * @throws Malformed for a quote left open at the end of the text, at the line it opened on, or for text after a
     *                   closing quote
     */
    static List<Record> read(String text, char separator) throws Malformed {
        List<Record> records = new ArrayList<>();
        int length = text.length();
        int at = length > 0 && text.charAt(0) == '\uFEFF' ? 1 : 0;
        int line = 1;
        while (at < length) {
            int first = line;
            List<String> fields = new ArrayList<>();
            boolean more = true;
            while (more) {
                if (at < length && text.charAt(at) == '"') {
                    int opened = line;
                    StringBuilder field = new StringBuilder();
                    at++;
                    while (true) {
                        if (at >= length) {
                            throw new Malformed(opened, "unterminated quoted field");
                        }
                        char c = text.charAt(at++);
                        if (c == '"') {
                            if (at < length && text.charAt(at) == '"') {
                                field.append('"');
                                at++;
                                continue;
                            }
                            break;
                        }
                        if (c == '\n') {
                            line++;
                        }
                        field.append(c);
                    }
                    fields.add(field.toString());
                } else {
                    int from = at;
                    while (at < length && text.charAt(at) != separator && lineEnd(text, at) == 0) {
                        at++;
                    }
                    fields.add(at == from ? null : text.substring(from, at));
                }
                // after a field: the separator, a line end or the end of the text; after a quote, nothing else
                if (at >= length) {
                    more = false;
                } else if (text.charAt(at) == separator) {
                    at++;
                } else if (lineEnd(text, at) > 0) {
                    at += lineEnd(text, at);
                    line++;
                    more = false;
                } else {
                    throw new Malformed(line, "text after a closing quote");
                }
            }
            records.add(new Record(first, Collections.unmodifiableList(fields)));
        }
        return records;
    }

    /** The length of the line end at {@code at}: 2 for {@code \r\n}, 1 for {@code \n}, 0 for none. */
    private static int lineEnd(String text, int at) {
        char c = text.charAt(at);
        if (c == '\n') {
            return 1;
        }
        return c == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n' ? 2 : 0;
    }

    /** Writes records up to a length in UTF-8 bytes; a record that would pass it is refused, the text kept whole. */
    static final class Writer {

        private final char separator;
        private final long maxBytes;
        private final StringBuilder text = new StringBuilder();
        private long bytes;
        private int records;

        /**
         * @param separator between the fields, {@code ,} or {@code ;}
         * @param maxBytes  the longest text, in UTF-8 bytes
         */
        Writer(char separator, long maxBytes) {
            this.separator = separator;
            this.maxBytes = maxBytes;
        }

        /**
         * Appends one record, each field quoted as needed, then {@value Csv#LINE_END}.
         *
         * @return {@code false}, and nothing appended, when the text would then pass the limit
         */
        boolean add(List<String> fields) {
            StringBuilder record = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    record.append(separator);
                }
                record.append(field(fields.get(i), separator));
            }
            record.append(LINE_END);
            long size = utf8Length(record);
            if (bytes + size > maxBytes) {
                return false;
            }
            text.append(record);
            bytes += size;
            records++;
            return true;
        }

        String text() {
            return text.toString();
        }

        /** The text's length in UTF-8 bytes. */
        long bytes() {
            return bytes;
        }

        /** The records written, the header included. */
        int records() {
            return records;
        }
    }
}
