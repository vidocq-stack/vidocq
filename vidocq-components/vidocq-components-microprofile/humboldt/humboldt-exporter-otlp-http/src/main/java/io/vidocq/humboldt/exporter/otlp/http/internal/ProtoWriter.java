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
package io.vidocq.humboldt.exporter.otlp.http.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The few pieces of the protobuf binary wire format the OTLP messages need, written into one growing buffer:
 * varints, little-endian fixed 32/64-bit values, length-delimited strings, bytes and embedded messages.
 *
 * <p>Encoding: <a href="https://protobuf.dev/programming-guides/encoding/">protobuf.dev, Encoding</a>. A field of
 * proto3 without presence that holds its default value (0, {@code false}, empty) is not written, as protoc's
 * encoders do; the {@code *Always} methods write a field that has presence (a {@code oneof} member, an
 * {@code optional} field) whatever its value.</p>
 *
 * <p>An embedded message is written in place: {@link #beginMessage} writes its tag and returns where its content
 * starts, {@link #endMessage} then moves that content right by the size of its length varint and writes the
 * length in front. No size pass, no buffer per message.</p>
 *
 * <p>Not thread-safe: one writer per encoded request.</p>
 */
final class ProtoWriter {

    private static final int VARINT = 0;
    private static final int FIXED64 = 1;
    private static final int LENGTH_DELIMITED = 2;
    private static final int FIXED32 = 5;

    private byte[] buffer = new byte[512];
    private int position;

    /** The bytes written so far. */
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, position);
    }

    // ------------------------------------------------------------------ fields

    /** A varint field ({@code int32}, {@code int64}, {@code uint32}, an enum), unless it is 0. */
    void varintField(int field, long value) {
        if (value != 0) {
            varintAlways(field, value);
        }
    }

    /** A varint field, written even when it is 0. */
    void varintAlways(int field, long value) {
        tag(field, VARINT);
        varint(value);
    }

    /** A {@code bool} field, unless it is {@code false}. */
    void boolField(int field, boolean value) {
        if (value) {
            varintAlways(field, 1);
        }
    }

    /** A {@code bool} field, written even when it is {@code false}. */
    void boolAlways(int field, boolean value) {
        varintAlways(field, value ? 1 : 0);
    }

    /** A {@code fixed64} or {@code sfixed64} field, unless it is 0. */
    void fixed64Field(int field, long value) {
        if (value != 0) {
            fixed64Always(field, value);
        }
    }

    /** A {@code fixed64} or {@code sfixed64} field, written even when it is 0. */
    void fixed64Always(int field, long value) {
        tag(field, FIXED64);
        fixed64(value);
    }

    /** A {@code fixed32} field, unless it is 0. */
    void fixed32Field(int field, int value) {
        if (value != 0) {
            tag(field, FIXED32);
            ensure(4);
            for (int i = 0; i < 4; i++) {
                buffer[position++] = (byte) (value >>> (8 * i));
            }
        }
    }

    /** A {@code double} field, written even when it is 0 (for a {@code oneof} member or an {@code optional}). */
    void doubleAlways(int field, double value) {
        fixed64Always(field, Double.doubleToRawLongBits(value));
    }

    /** A {@code string} field, unless it is {@code null} or empty. */
    void stringField(int field, String value) {
        if (value != null && !value.isEmpty()) {
            stringAlways(field, value);
        }
    }

    /** A {@code string} field, written even when it is empty ({@code null} is written as empty). */
    void stringAlways(int field, String value) {
        bytesAlways(field, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code bytes} field, unless it is empty. */
    void bytesField(int field, byte[] value) {
        if (value.length > 0) {
            bytesAlways(field, value);
        }
    }

    /** A {@code bytes} field, written even when it is empty. */
    void bytesAlways(int field, byte[] value) {
        tag(field, LENGTH_DELIMITED);
        varint(value.length);
        ensure(value.length);
        System.arraycopy(value, 0, buffer, position, value.length);
        position += value.length;
    }

    /**
     * Starts an embedded message, or a packed repeated field, numbered {@code field}: its content follows, then
     * {@link #endMessage} with the returned mark.
     */
    int beginMessage(int field) {
        tag(field, LENGTH_DELIMITED);
        return position;
    }

    /** Ends the embedded message {@link #beginMessage} started at {@code mark}: writes its length in front of it. */
    void endMessage(int mark) {
        int length = position - mark;
        int lengthSize = varintSize(length);
        ensure(lengthSize);
        System.arraycopy(buffer, mark, buffer, mark + lengthSize, length);
        int end = position + lengthSize;
        position = mark;
        varint(length);
        position = end;
    }

    /** One element of a packed {@code repeated fixed64} or {@code repeated double}, between begin and end. */
    void packedFixed64(long value) {
        fixed64(value);
    }

    // ------------------------------------------------------------------ wire

    private void tag(int field, int wireType) {
        varint(((long) field << 3) | wireType);
    }

    /** A base-128 varint; a negative {@code int64} takes its ten bytes, as protobuf writes it. */
    private void varint(long value) {
        ensure(10);
        long v = value;
        while ((v & ~0x7FL) != 0) {
            buffer[position++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buffer[position++] = (byte) v;
    }

    private void fixed64(long value) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buffer[position++] = (byte) (value >>> (8 * i));
        }
    }

    private static int varintSize(int value) {
        int size = 1;
        int v = value;
        while ((v & ~0x7F) != 0) {
            size++;
            v >>>= 7;
        }
        return size;
    }

    private void ensure(int more) {
        int needed = position + more;
        if (needed > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(needed, buffer.length * 2));
        }
    }
}
