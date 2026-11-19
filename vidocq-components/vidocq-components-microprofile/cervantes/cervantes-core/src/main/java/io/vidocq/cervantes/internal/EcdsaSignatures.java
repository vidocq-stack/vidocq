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
package io.vidocq.cervantes.internal;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Transcoding ECDSA signatures between the JOSE form (concatenation {@code R‖S}, RFC 7518 §3.4)
 * and the DER form {@code SEQUENCE(INTEGER r, INTEGER s)} expected by {@code java.security.Signature}.
 *
 * <p>JWS {@code ES256/384/512} bear a gross signature of {@code 2·n} bytes ({@code n} =
 * 32/48/66 according to the curve), while JCA produces and consumes DER. Without this transcoding,
 * {@code Signature.verify(...)} fails systematically on a legitimate ECDSA token.</p>
 */
final class EcdsaSignatures {

    private EcdsaSignatures() {}

    /** JOSE {@code R‖S} (each coordinate over {@code n} bytes) → DER {@code SEQUENCE(INTEGER, INTEGER)}. */
    static byte[] joseToDer(byte[] jose, int n) {
        if (jose.length != 2 * n) {
            throw new IllegalArgumentException(
                    "invalid JOSE ECDSA signature length " + jose.length + " (expected " + (2 * n) + ")");
        }
        byte[] rEnc = asn1Integer(trimLeadingZeros(jose, 0, n));
        byte[] sEnc = asn1Integer(trimLeadingZeros(jose, n, n));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        writeLength(out, rEnc.length + sEnc.length);
        out.write(rEnc, 0, rEnc.length);
        out.write(sEnc, 0, sEnc.length);
        return out.toByteArray();
    }

    /** DER {@code SEQUENCE(INTEGER r, INTEGER s)} → JOSE {@code R‖S} (each coordinate over {@code n} bytes). */
    static byte[] derToJose(byte[] der, int n) {
        int p = 0;
        if (der[p++] != 0x30) throw new IllegalArgumentException("not a DER SEQUENCE");
        int first = der[p] & 0xff;
        if (first < 0x80) p += 1;
        else if (first == 0x81) p += 2;
        else if (first == 0x82) p += 3;
        else throw new IllegalArgumentException("unsupported DER length encoding");
        int[] cur = {p};
        byte[] r = readInteger(der, cur);
        byte[] s = readInteger(der, cur);
        byte[] out = new byte[2 * n];
        copyRightAligned(r, out, 0, n);
        copyRightAligned(s, out, n, n);
        return out;
    }

    // --- DER helpers -------------------------------------------------------

    private static byte[] asn1Integer(byte[] magnitude) {
        boolean pad = (magnitude[0] & 0x80) != 0; //sign bit: prefix 0x00 to stay positive
        int len = magnitude.length + (pad ? 1 : 0);
        byte[] out = new byte[2 + len];
        out[0] = 0x02;
        out[1] = (byte) len; //len <= 67 for P-521 → long encoding on a single byte
        int idx = 2;
        if (pad) out[idx++] = 0;
        System.arraycopy(magnitude, 0, out, idx, magnitude.length);
        return out;
    }

    private static byte[] readInteger(byte[] der, int[] cur) {
        int p = cur[0];
        if (der[p++] != 0x02) throw new IllegalArgumentException("expected DER INTEGER");
        int len = der[p++] & 0xff; //EC coordinates: length < 128
        byte[] v = Arrays.copyOfRange(der, p, p + len);
        cur[0] = p + len;
        int start = 0;
        while (start < v.length - 1 && v[start] == 0) start++; // strip sign 0x00
        return Arrays.copyOfRange(v, start, v.length);
    }

    private static void writeLength(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
    }

    private static byte[] trimLeadingZeros(byte[] a, int off, int n) {
        int start = off;
        int end = off + n;
        while (start < end - 1 && a[start] == 0) start++;
        return Arrays.copyOfRange(a, start, end);
    }

    private static void copyRightAligned(byte[] src, byte[] dst, int off, int n) {
        if (src.length > n) throw new IllegalArgumentException("ECDSA coordinate larger than " + n + " octets");
        System.arraycopy(src, 0, dst, off + (n - src.length), src.length);
    }
}
