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
import jakarta.json.JsonValue;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leaves a JWK Set (RFC 7517) — or a single JWK — in {@link Jwks} public keys,
 * without third-party crypt dependence (reconstruction RSA via {@code n}/{@code e} and EC via
 * {@code crv}/{@code x}/{@code y} with the JCA).
 *
 * <p>Encryption keys ({@code use:"enc"}) and unsupported {@code kty} are ignored;
 * an invalid individual key is ignored rather than fail the entire set.ZZPH0ZZ
 */
final class JwkParser {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    private JwkParser() {}

    static Jwks parse(byte[] json) throws JwtValidationException {
        JsonObject root;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(json))) {
            root = reader.readObject();
        } catch (RuntimeException e) {
            throw new JwtValidationException("invalid JWKS JSON", e);
        }

        List<JsonObject> jwks = new ArrayList<>();
        if (root.containsKey("keys") && root.get("keys").getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue v : root.getJsonArray("keys")) {
                if (v.getValueType() == JsonValue.ValueType.OBJECT) jwks.add(v.asJsonObject());
            }
        } else {
            jwks.add(root); // JWK unique
        }

        Map<String, PublicKey> byKid = new LinkedHashMap<>();
        List<PublicKey> all = new ArrayList<>();
        for (JsonObject jwk : jwks) {
            if ("enc".equals(string(jwk, "use"))) continue; //signature keys only
            PublicKey key = toPublicKey(jwk);
            if (key == null) continue;
            all.add(key);
            String kid = string(jwk, "kid");
            if (kid != null) byKid.put(kid, key);
        }
        return new Jwks(byKid, all);
    }

    private static PublicKey toPublicKey(JsonObject jwk) {
        try {
            String kty = string(jwk, "kty");
            if (kty == null) return null;
            return switch (kty) {
                case "RSA" -> rsaKey(jwk);
                case "EC" -> ecKey(jwk);
                default -> null; //Oct (HMAC) and other unsupported
            };
        } catch (GeneralSecurityException | RuntimeException e) {
            return null; //individual key illegible: we ignore it
        }
    }

    private static PublicKey rsaKey(JsonObject jwk) throws GeneralSecurityException {
        BigInteger n = uint(jwk, "n");
        BigInteger e = uint(jwk, "e");
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    }

    private static PublicKey ecKey(JsonObject jwk) throws GeneralSecurityException {
        String stdName = switch (string(jwk, "crv")) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            case null, default -> throw new IllegalArgumentException("unsupported EC curve");
        };
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(stdName));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(uint(jwk, "x"), uint(jwk, "y"));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
    }

    /**
     * Parse un JWK (single) ou JWKS (set with "keys") JSON document and returns the first RSA
     * private key found. Used for {@code mp.jwt.decrypt.key.location} pointing to a JWK file.
     *
     * @param json raw JWK or JWKS JSON bytes
     * @return the RSA private key from the first matching JWK entry
     * @throws JwtValidationException if no RSA private key is found or parsing fails
     */
    static PrivateKey parsePrivateKey(byte[] json) throws JwtValidationException {
        JsonObject root;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(json))) {
            root = reader.readObject();
        } catch (RuntimeException e) {
            throw new JwtValidationException("invalid JWK(S) JSON for private key", e);
        }

        List<JsonObject> jwks = new ArrayList<>();
        if (root.containsKey("keys") && root.get("keys").getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue v : root.getJsonArray("keys")) {
                if (v.getValueType() == JsonValue.ValueType.OBJECT) jwks.add(v.asJsonObject());
            }
        } else {
            jwks.add(root);
        }

        for (JsonObject jwk : jwks) {
            String kty = string(jwk, "kty");
            if (!"RSA".equals(kty)) continue;
            // Must have 'd' (private exponent) to be a private key
            if (string(jwk, "d") == null) continue;
            try {
                return rsaPrivateKey(jwk);
            } catch (GeneralSecurityException | RuntimeException e) {
                // Try next key if available
            }
        }
        throw new JwtValidationException("no RSA private key found in JWK(S) document");
    }

    private static PrivateKey rsaPrivateKey(JsonObject jwk) throws GeneralSecurityException {
        BigInteger n = uint(jwk, "n");
        BigInteger e = uint(jwk, "e");
        BigInteger d = uint(jwk, "d");
        // CRT parameters are optional but present in most JWK private keys
        BigInteger p = uintOrNull(jwk, "p");
        BigInteger q = uintOrNull(jwk, "q");
        BigInteger dp = uintOrNull(jwk, "dp");
        BigInteger dq = uintOrNull(jwk, "dq");
        BigInteger qi = uintOrNull(jwk, "qi");
        if (p != null && q != null && dp != null && dq != null && qi != null) {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qi));
        }
        // Fallback: minimal RSA private key spec (no CRT)
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.RSAPrivateKeySpec(n, d));
    }

    /** Unsigned big-endian integer from a base64url member of the JWK. */
    private static BigInteger uint(JsonObject jwk, String member) {
        String s = string(jwk, member);
        if (s == null) throw new IllegalArgumentException("missing JWK member '" + member + "'");
        return new BigInteger(1, B64URL.decode(s));
    }

    private static BigInteger uintOrNull(JsonObject jwk, String member) {
        String s = string(jwk, member);
        return s == null ? null : new BigInteger(1, B64URL.decode(s));
    }

    private static String string(JsonObject o, String name) {
        JsonValue v = o.get(name);
        return (v != null && v.getValueType() == JsonValue.ValueType.STRING)
                ? ((jakarta.json.JsonString) v).getString() : null;
    }
}
