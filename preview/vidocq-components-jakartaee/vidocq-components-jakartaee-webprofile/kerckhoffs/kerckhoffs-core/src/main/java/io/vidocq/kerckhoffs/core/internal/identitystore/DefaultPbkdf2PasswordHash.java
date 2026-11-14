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

import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The built-in {@link Pbkdf2PasswordHash} (Jakarta Security 4.0): PBKDF2 with HMAC, hashes encoded as
 * {@code algorithm:iterations:base64(salt):base64(hash)}.
 *
 * <p>The algorithm and the cost are stored with the hash, so the configuration can change without
 * invalidating existing hashes. A stored hash outside the supported range cannot be verified: it
 * answers {@code false}, it never throws, and the derived key is compared in constant time.
 */
public final class DefaultPbkdf2PasswordHash implements Pbkdf2PasswordHash {

    static final String ALGORITHM = "Pbkdf2PasswordHash.Algorithm";
    static final String ITERATIONS = "Pbkdf2PasswordHash.Iterations";
    static final String SALT_SIZE_BYTES = "Pbkdf2PasswordHash.SaltSizeBytes";
    static final String KEY_SIZE_BYTES = "Pbkdf2PasswordHash.KeySizeBytes";

    private static final Set<String> ALGORITHMS = Set.of("PBKDF2WithHmacSHA224", "PBKDF2WithHmacSHA256",
            "PBKDF2WithHmacSHA384", "PBKDF2WithHmacSHA512");
    private static final int MIN_ITERATIONS = 1024;
    private static final int MIN_SALT_SIZE_BYTES = 16;
    private static final int MIN_KEY_SIZE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private String algorithm = "PBKDF2WithHmacSHA256";
    private int iterations = 2048;
    private int saltSizeBytes = 32;
    private int keySizeBytes = 32;

    @Override
    public void initialize(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        String requested = parameters.getOrDefault(ALGORITHM, algorithm);
        if (!ALGORITHMS.contains(requested)) {
            throw new IllegalArgumentException(ALGORITHM + " must be one of " + new TreeSet<>(ALGORITHMS)
                    + ", not " + requested);
        }
        int newIterations = atLeast(parameters, ITERATIONS, iterations, MIN_ITERATIONS);
        int newSaltSize = atLeast(parameters, SALT_SIZE_BYTES, saltSizeBytes, MIN_SALT_SIZE_BYTES);
        int newKeySize = atLeast(parameters, KEY_SIZE_BYTES, keySizeBytes, MIN_KEY_SIZE_BYTES);
        this.algorithm = requested;
        this.iterations = newIterations;
        this.saltSizeBytes = newSaltSize;
        this.keySizeBytes = newKeySize;
    }

    @Override
    public String generate(char[] password) {
        Objects.requireNonNull(password, "password");
        byte[] salt = new byte[saltSizeBytes];
        RANDOM.nextBytes(salt);
        byte[] key = derive(algorithm, password, salt, iterations, keySizeBytes);
        try {
            Base64.Encoder encoder = Base64.getEncoder();
            return algorithm + ':' + iterations + ':' + encoder.encodeToString(salt) + ':' + encoder.encodeToString(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public boolean verify(char[] password, String hashedPassword) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(hashedPassword, "hashedPassword");
        Stored stored;
        try {
            stored = Stored.parse(hashedPassword);
        } catch (IllegalArgumentException cannotBeVerified) {
            return false;
        }
        byte[] actual = derive(stored.algorithm(), password, stored.salt(), stored.iterations(), stored.hash().length);
        try {
            return MessageDigest.isEqual(stored.hash(), actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    /** A stored hash, read back: what it was made with and the derived key. */
    private record Stored(String algorithm, int iterations, byte[] salt, byte[] hash) {

        /**
         * @throws IllegalArgumentException when the text is not a hash this implementation can verify:
         *                                  malformed, an unsupported algorithm, or below the minimums
         */
        static Stored parse(String encoded) {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 4 || !ALGORITHMS.contains(parts[0])) {
                throw new IllegalArgumentException("Not a supported PBKDF2 hash");
            }
            int cost = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (cost < MIN_ITERATIONS || salt.length < MIN_SALT_SIZE_BYTES || hash.length < MIN_KEY_SIZE_BYTES) {
                throw new IllegalArgumentException("A PBKDF2 hash below the supported minimums");
            }
            return new Stored(parts[0], cost, salt, hash);
        }
    }

    private static int atLeast(Map<String, String> parameters, String name, int current, int minimum) {
        String value = parameters.get(name);
        if (value == null) {
            return current;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, not " + value, e);
        }
        if (parsed < minimum) {
            throw new IllegalArgumentException(name + " must be at least " + minimum + ", not " + parsed);
        }
        return parsed;
    }

    private static byte[] derive(String algorithm, char[] password, byte[] salt, int iterations, int keySizeBytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, Math.multiplyExact(keySizeBytes, 8));
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The " + algorithm + " key derivation is not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
