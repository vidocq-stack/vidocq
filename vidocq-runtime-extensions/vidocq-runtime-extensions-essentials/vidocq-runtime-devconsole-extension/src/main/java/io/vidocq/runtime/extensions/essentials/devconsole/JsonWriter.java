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
package io.vidocq.runtime.extensions.essentials.devconsole;

/**
 * Writes one JSON text (RFC 8259) front to back, by hand: the console depends on no JSON library. The caller opens
 * and closes objects and arrays in order and names each member of an object; the writer puts the commas.
 *
 * <p>A string is escaped for JSON only: a quote, a backslash, the C0 and C1 control characters, the line and paragraph
 * separators and a surrogate without its pair are written as escapes, everything else as it is. A number that is not
 * finite is written {@code null}, which JSON has for it; a whole number is written without a fraction.
 */
final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StringBuilder out = new StringBuilder(4096);
    /** Whether the current object or array already holds a value, so that the next one needs a comma. */
    private boolean comma;
    /** Whether a name was just written, so that the value that follows it needs no comma. */
    private boolean named;

    JsonWriter beginObject() {
        beforeValue();
        out.append('{');
        comma = false;
        return this;
    }

    JsonWriter endObject() {
        out.append('}');
        comma = true;
        return this;
    }

    JsonWriter beginArray() {
        beforeValue();
        out.append('[');
        comma = false;
        return this;
    }

    JsonWriter endArray() {
        out.append(']');
        comma = true;
        return this;
    }

    /** The name of the next member of the current object. */
    JsonWriter name(String name) {
        if (comma) {
            out.append(',');
        }
        string(name);
        out.append(':');
        named = true;
        return this;
    }

    JsonWriter value(String value) {
        beforeValue();
        if (value == null) {
            out.append("null");
        } else {
            string(value);
        }
        comma = true;
        return this;
    }

    JsonWriter value(long value) {
        beforeValue();
        out.append(value);
        comma = true;
        return this;
    }

    JsonWriter value(double value) {
        if (!Double.isFinite(value)) {
            return nullValue();
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return value((long) value);
        }
        beforeValue();
        out.append(value);
        comma = true;
        return this;
    }

    JsonWriter value(boolean value) {
        beforeValue();
        out.append(value);
        comma = true;
        return this;
    }

    JsonWriter nullValue() {
        beforeValue();
        out.append("null");
        comma = true;
        return this;
    }

    /** The text written so far. */
    @Override
    public String toString() {
        return out.toString();
    }

    private void beforeValue() {
        if (named) {
            named = false;
        } else if (comma) {
            out.append(',');
        }
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == '\u2028' || c == '\u2029') {
                        escape(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        escape(c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void escape(char c) {
        out.append("\\u").append(HEX[c >> 12 & 0xf]).append(HEX[c >> 8 & 0xf]).append(HEX[c >> 4 & 0xf])
                .append(HEX[c & 0xf]);
    }
}
