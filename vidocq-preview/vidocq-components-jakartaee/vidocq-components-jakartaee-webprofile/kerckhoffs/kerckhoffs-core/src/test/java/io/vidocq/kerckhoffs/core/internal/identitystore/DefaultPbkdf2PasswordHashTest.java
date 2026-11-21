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
package io.vidocq.kerckhoffs.core.internal.identitystore;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in {@code Pbkdf2PasswordHash} (Jakarta Security 4.0, interface javadoc): the encoded format
 * {@code algorithm:iterations:base64(salt):base64(hash)}, the four parameters and their minimums.
 * Failures to verify are answers, never exceptions: a hash that cannot be verified is a hash that does
 * not match.
 */
class DefaultPbkdf2PasswordHashTest {

    private static final String ALGORITHM = "Pbkdf2PasswordHash.Algorithm";
    private static final String ITERATIONS = "Pbkdf2PasswordHash.Iterations";
    private static final String SALT_SIZE = "Pbkdf2PasswordHash.SaltSizeBytes";
    private static final String KEY_SIZE = "Pbkdf2PasswordHash.KeySizeBytes";

    // Reference values computed outside Java: hashlib.pbkdf2_hmac(alg, b"secret1", bytes(range(1, 17)), c, n)
    private static final String SALT = "AQIDBAUGBwgJCgsMDQ4PEA==";
    private static final String SHA256_2048 = "PBKDF2WithHmacSHA256:2048:" + SALT
            + ":TV6ztRcY63Lqdr/6Fpu8f4HeB6sfj6qe3+WqGfpxgVk=";
    private static final String SHA512_4096 = "PBKDF2WithHmacSHA512:4096:" + SALT
            + ":0oPJGa8CK3xeNMpazmTHUCT8FpNMvrv+P1qricgt4fUGrBav1V1yWyEml5gwVF2FHme7LB+wTdeLSEqWRm+UdA==";

    private static DefaultPbkdf2PasswordHash configured(Map<String, String> parameters) {
        var hash = new DefaultPbkdf2PasswordHash();
        hash.initialize(parameters);
        return hash;
    }

    // ---- what must not verify

    @Test
    void a_wrong_password_does_not_verify() {
        assertFalse(new DefaultPbkdf2PasswordHash().verify("wrong".toCharArray(), SHA256_2048));
    }

    @Test
    void a_malformed_hash_does_not_verify() {
        var hash = new DefaultPbkdf2PasswordHash();
        for (String malformed : new String[] {"", "secret1", "a:b:c", "PBKDF2WithHmacSHA256:2048:" + SALT,
                "PBKDF2WithHmacSHA256:many:" + SALT + ":AAAA", "PBKDF2WithHmacSHA256:2048:%%%:AAAA",
                SHA256_2048 + ":extra"}) {
            assertFalse(hash.verify("secret1".toCharArray(), malformed), malformed);
        }
    }

    @Test
    void an_algorithm_outside_the_supported_four_does_not_verify() {
        var sha1 = "PBKDF2WithHmacSHA1:2048:" + SALT + ":TV6ztRcY63Lqdr/6Fpu8f4HeB6sfj6qe3+WqGfpxgVk=";

        assertFalse(new DefaultPbkdf2PasswordHash().verify("secret1".toCharArray(), sha1));
    }

    @Test
    void a_hash_below_the_minimums_does_not_verify() {
        // Each hash below is the CORRECT hash of "secret1" for its own parameters (computed outside Java),
        // so only the minimums can make it fail: a wrong hash would be refused for the wrong reason.
        var hash = new DefaultPbkdf2PasswordHash();
        var tooFewIterations = "PBKDF2WithHmacSHA256:1023:" + SALT + ":fJs/67bh5kp9kFaAlpdpox78zBDlSlgc/sCULp9Xz2g=";
        var shortSalt = "PBKDF2WithHmacSHA256:2048:AQIDBAUGBwgJCgsMDQ4P"
                + ":voAtcNGR5sFznCJ+FJQxqNaR/1WwJy6XpyPM1c2yFqU=";
        var shortHash = "PBKDF2WithHmacSHA256:2048:" + SALT + ":TV6ztRcY63Lqdr/6Fpu8";

        assertFalse(hash.verify("secret1".toCharArray(), tooFewIterations));
        assertFalse(hash.verify("secret1".toCharArray(), shortSalt));
        assertFalse(hash.verify("secret1".toCharArray(), shortHash));
    }

    @Test
    void a_null_argument_is_refused() {
        var hash = new DefaultPbkdf2PasswordHash();
        char[] password = "secret1".toCharArray();

        assertThrows(NullPointerException.class, () -> hash.generate(null));
        assertThrows(NullPointerException.class, () -> hash.verify(null, SHA256_2048));
        assertThrows(NullPointerException.class, () -> hash.verify(password, null));
    }

    // ---- what must verify

    @Test
    void the_reference_hashes_verify() {
        var hash = new DefaultPbkdf2PasswordHash();

        assertTrue(hash.verify("secret1".toCharArray(), SHA256_2048));
        assertTrue(hash.verify("secret1".toCharArray(), SHA512_4096));
    }

    @Test
    void a_password_is_not_wiped_by_the_hash_it_is_checked_against() {
        char[] password = "secret1".toCharArray();

        new DefaultPbkdf2PasswordHash().verify(password, SHA256_2048);

        assertArrayEquals("secret1".toCharArray(), password, "the caller owns and wipes its own array");
    }

    // ---- generate

    @Test
    void generate_uses_the_documented_defaults_and_format() {
        var encoded = new DefaultPbkdf2PasswordHash().generate("secret1".toCharArray());

        var parts = encoded.split(":");
        assertEquals(4, parts.length);
        assertEquals("PBKDF2WithHmacSHA256", parts[0]);
        assertEquals("2048", parts[1]);
        assertEquals(32, Base64.getDecoder().decode(parts[2]).length);
        assertEquals(32, Base64.getDecoder().decode(parts[3]).length);
    }

    @Test
    void generate_then_verify_round_trips() {
        var hash = new DefaultPbkdf2PasswordHash();

        var encoded = hash.generate("secret1".toCharArray());

        assertTrue(hash.verify("secret1".toCharArray(), encoded));
        assertFalse(hash.verify("secret2".toCharArray(), encoded));
    }

    @Test
    void two_hashes_of_the_same_password_differ_by_their_salt() {
        var hash = new DefaultPbkdf2PasswordHash();

        assertNotEquals(hash.generate("secret1".toCharArray()), hash.generate("secret1".toCharArray()));
    }

    // ---- configuration

    @Test
    void the_four_parameters_are_applied() {
        var hash = configured(Map.of(ALGORITHM, "PBKDF2WithHmacSHA512", ITERATIONS, "4096", SALT_SIZE, "16",
                KEY_SIZE, "64"));

        var parts = hash.generate("secret1".toCharArray()).split(":");

        assertEquals("PBKDF2WithHmacSHA512", parts[0]);
        assertEquals("4096", parts[1]);
        assertEquals(16, Base64.getDecoder().decode(parts[2]).length);
        assertEquals(64, Base64.getDecoder().decode(parts[3]).length);
    }

    @Test
    void a_hash_made_with_other_parameters_still_verifies() {
        var hash = configured(Map.of(ALGORITHM, "PBKDF2WithHmacSHA512", ITERATIONS, "1024"));

        assertTrue(hash.verify("secret1".toCharArray(), SHA256_2048), "the parameters are stored with the hash");
    }

    @Test
    void parameters_outside_the_allowed_range_are_refused_at_initialization() {
        for (Map<String, String> invalid : java.util.List.of(
                Map.of(ITERATIONS, "1023"), Map.of(SALT_SIZE, "15"), Map.of(KEY_SIZE, "15"),
                Map.of(ALGORITHM, "PBKDF2WithHmacSHA1"), Map.of(ITERATIONS, "lots"))) {
            var hash = new DefaultPbkdf2PasswordHash();
            assertThrows(IllegalArgumentException.class, () -> hash.initialize(invalid), invalid.toString());
        }
    }

    @Test
    void initializing_with_nothing_keeps_the_defaults() {
        var hash = configured(Map.of());

        assertTrue(hash.generate("secret1".toCharArray()).startsWith("PBKDF2WithHmacSHA256:2048:"));
    }
}
