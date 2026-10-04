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

import io.vidocq.cervantes.api.JwtValidationException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Decrypts an encrypted JWT (JWE compact, 5 parts) in its nested JWS (MicroProfile JWT 2.2:
 * <em>sign-then-encrypt</em>). Zero third-party cryptography dependency — {@code javax.crypto} only.
 *
 * <p>Key management: {@code RSA-OAEP} (SHA-1) and {@code RSA-OAEP-256} (SHA-256). Content encryption:
 * {@code A256GCM} (AES-256-GCM, 96-bit IV, 128-bit tag, AAD = ASCII(base64url(header))).
 * The clear text obtained is a compact JWS, then validated by {@link DefaultJwtValidator}.</p>
 */
public final class JweDecryptor {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final int GCM_TAG_BITS = 128;

    /** The JWE key-management algorithms this decryptor can unwrap (exact, case-sensitive JOSE names). */
    public static final java.util.List<String> SUPPORTED_ALGORITHMS = java.util.List.of("RSA-OAEP", "RSA-OAEP-256");

    private final PrivateKey decryptionKey;
    /**
     * If non-null, only JWE tokens whose header {@code alg} exactly matches this value are
     * Accepted. Strengths {@code mp.jwt.decrypt.key.algorithm} (MP JWT spec §9.2.4).
     */
    private final String requiredAlgorithm;

    public JweDecryptor(PrivateKey decryptionKey) {
        this(decryptionKey, null);
    }

    public JweDecryptor(PrivateKey decryptionKey, String requiredAlgorithm) {
        this.decryptionKey = Objects.requireNonNull(decryptionKey, "decryptionKey");
        this.requiredAlgorithm = requiredAlgorithm; // nullable
    }

    /** @return the nested compact JWS (to validate afterwards). */
    public String decryptToCompactJws(String jwe) throws JwtValidationException {
        String[] parts = jwe.split("\\.", -1);
        if (parts.length != 5) {
            throw new JwtValidationException("a JWE compact serialization must have 5 parts, got " + parts.length);
        }
        JsonObject header = readHeader(parts[0]);
        String alg = stringMember(header, "alg");
        String enc = stringMember(header, "enc");

        //MP JWT spec §9.2.4: if mp.jwt.decrypt.key.algorithm is configured, forces it.
        if (requiredAlgorithm != null && !requiredAlgorithm.equals(alg)) {
            throw new JwtValidationException(
                    "JWE key-management algorithm mismatch: configured=" + requiredAlgorithm + ", token=" + alg);
        }

        //MP JWT spec §9.2: JWE must have cty="JWT" to indicate the payload is a registered JWT (JWS).
        String cty = stringMember(header, "cty");
        if (!"JWT".equalsIgnoreCase(cty)) {
            throw new JwtValidationException(
                    "JWE 'cty' header must be 'JWT' for nested JWT, got: " + cty);
        }

        byte[] cek = unwrapContentKey(B64URL.decode(parts[1]), alg);
        byte[] aad = parts[0].getBytes(StandardCharsets.US_ASCII);
        byte[] plaintext = decryptContent(enc, cek,
                B64URL.decode(parts[2]), B64URL.decode(parts[3]), B64URL.decode(parts[4]), aad);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private byte[] unwrapContentKey(byte[] encryptedKey, String alg) throws JwtValidationException {
        OAEPParameterSpec oaep = switch (alg) {
            case "RSA-OAEP" ->
                    new OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
            case "RSA-OAEP-256" ->
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            case null, default -> throw new JwtValidationException("unsupported JWE key-management alg: " + alg);
        };
        try {
            //"RSA/ECB/OAEPPadding" + OAEPParameterExplicitSpec: avoids the default MGF1=SHA-1 trap.
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, decryptionKey, oaep);
            return cipher.doFinal(encryptedKey);
        } catch (GeneralSecurityException e) {
            throw new JwtValidationException("JWE content-key unwrap failed (" + alg + ")", e);
        }
    }

    private byte[] decryptContent(String enc, byte[] cek, byte[] iv, byte[] ciphertext, byte[] tag, byte[] aad)
            throws JwtValidationException {
        if (!"A256GCM".equals(enc)) {
            throw new JwtValidationException("unsupported JWE content-encryption enc: " + enc);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            //The JCA is waiting for concatenated ciphertext. JWE separates them.
            byte[] combined = Arrays.copyOf(ciphertext, ciphertext.length + tag.length);
            System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
            return cipher.doFinal(combined);
        } catch (GeneralSecurityException e) {
            throw new JwtValidationException("JWE content decryption/authentication failed", e);
        }
    }

    private static JsonObject readHeader(String segment) throws JwtValidationException {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(B64URL.decode(segment)))) {
            return reader.readObject();
        } catch (RuntimeException e) {
            throw new JwtValidationException("invalid JWE protected header", e);
        }
    }

    private static String stringMember(JsonObject o, String name) {
        JsonValue v = o.get(name);
        return (v instanceof JsonString s) ? s.getString() : null;
    }
}
