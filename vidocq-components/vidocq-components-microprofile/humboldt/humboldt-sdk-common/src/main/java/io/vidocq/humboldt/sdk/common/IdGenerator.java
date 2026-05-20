package io.vidocq.humboldt.sdk.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Générateur d'identifiants W3C TraceContext :
 * <ul>
 *   <li>{@code traceId} = 16 octets (128 bits), hex-encodé sur 32 caractères ASCII bas ;</li>
 *   <li>{@code spanId}  = 8 octets (64 bits), hex-encodé sur 16 caractères ASCII bas.</li>
 * </ul>
 *
 * <p>L'implémentation par défaut {@link Random128} utilise {@link ThreadLocalRandom}
 * pour rester non-contendue sur les virtual threads sans embarquer SecureRandom
 * (les identifiants de trace ne portent aucune garantie cryptographique selon
 * la spec OpenTelemetry).</p>
 */
public interface IdGenerator {

    /**
     * @return un nouveau traceId (32 caractères hexadécimaux), jamais "tout-zéro".
     */
    String generateTraceId();

    /**
     * @return un nouveau spanId (16 caractères hexadécimaux), jamais "tout-zéro".
     */
    String generateSpanId();

    /**
     * @return l'implémentation par défaut basée sur {@link ThreadLocalRandom}.
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
