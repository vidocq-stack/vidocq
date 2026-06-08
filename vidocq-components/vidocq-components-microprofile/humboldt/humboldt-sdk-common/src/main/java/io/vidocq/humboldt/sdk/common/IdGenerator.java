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
package io.vidocq.humboldt.sdk.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C TraceContext identifier generator:
 * <ul>
 *   <li>{@code traceId} = 16 bytes (128 bits), hex-encoded into 32 lowercase ASCII characters;</li>
 *   <li>{@code spanId}  = 8 bytes (64 bits), hex-encoded into 16 lowercase ASCII characters.</li>
 * </ul>
 *
 * <p>The default {@link Random128} implementation uses {@link ThreadLocalRandom}
 * to remain contention-free on virtual threads without embedding SecureRandom
 * (trace identifiers carry no cryptographic guarantee according to the
 * OpenTelemetry spec).</p>
 */
public interface IdGenerator {

    /**
     * @return a new traceId (32 hexadecimal characters), never "all-zero".
     */
    String generateTraceId();

    /**
     * @return a new spanId (16 hexadecimal characters), never "all-zero".
     */
    String generateSpanId();

    /**
     * @return the default implementation based on {@link ThreadLocalRandom}.
     */
    static IdGenerator random128() {
        return Random128.INSTANCE;
    }

    final class Random128 implements IdGenerator {

        static final Random128 INSTANCE = new Random128();
        private static final char[] HEX = "0123456789abcdef".toCharArray();

        @Override
        public String generateTraceId() {
            long hi;
            long lo;
            do {
                hi = ThreadLocalRandom.current().nextLong();
                lo = ThreadLocalRandom.current().nextLong();
            } while (hi == 0L && lo == 0L);
            char[] buf = new char[32];
            writeHex(hi, buf, 0);
            writeHex(lo, buf, 16);
            return new String(buf);
        }

        @Override
        public String generateSpanId() {
            long id;
            do {
                id = ThreadLocalRandom.current().nextLong();
            } while (id == 0L);
            char[] buf = new char[16];
            writeHex(id, buf, 0);
            return new String(buf);
        }

        private static void writeHex(long value, char[] buf, int offset) {
            for (int i = 15; i >= 0; i--) {
                buf[offset + i] = HEX[(int) (value & 0xF)];
                value >>>= 4;
            }
        }
    }
}
