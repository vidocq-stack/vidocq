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

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forge a JWK Set document (RFC 7517) from public keys — reverse of {@link JwkParser},
 * for testing. Without third-party library (encoding {@code n}/{@code e}, {@code crv}/{@code x}/{@code y}).
 */
final class TestJwks {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private TestJwks() {}

    static byte[] jwksJson(Map<String, PublicKey> keysByKid) {
        JsonArrayBuilder keys = Json.createArrayBuilder();
        keysByKid.forEach((kid, key) -> keys.add(jwk(kid, key)));
        return toBytes(Json.createObjectBuilder().add("keys", keys).build());
    }

    static byte[] jwksJson(String kid, PublicKey key) {
        Map<String, PublicKey> m = new LinkedHashMap<>();
        m.put(kid, key);
        return jwksJson(m);
    }

    static JsonObject jwk(String kid, PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            return Json.createObjectBuilder()
                    .add("kty", "RSA").add("kid", kid).add("use", "sig").add("alg", "RS256")
                    .add("n", b64(unsigned(rsa.getModulus())))
                    .add("e", b64(unsigned(rsa.getPublicExponent())))
                    .build();
        }
        if (key instanceof ECPublicKey ec) {
            int bits = ec.getParams().getCurve().getField().getFieldSize();
            int len = (bits + 7) / 8;
            String crv = switch (bits) {
                case 256 -> "P-256";
                case 384 -> "P-384";
                case 521 -> "P-521";
                default -> throw new IllegalArgumentException("unsupported curve size " + bits);
            };
            return Json.createObjectBuilder()
                    .add("kty", "EC").add("kid", kid).add("use", "sig").add("crv", crv)
                    .add("x", b64(fixed(ec.getW().getAffineX(), len)))
                    .add("y", b64(fixed(ec.getW().getAffineY(), len)))
                    .build();
        }
        throw new IllegalArgumentException("unsupported key type " + key.getClass());
    }

    private static String b64(byte[] b) {
        return B64URL.encodeToString(b);
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return (b.length > 1 && b[0] == 0) ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] fixed(BigInteger v, int len) {
        byte[] b = unsigned(v);
        if (b.length > len) throw new IllegalArgumentException("coordinate larger than " + len);
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }

    private static byte[] toBytes(JsonObject o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter w = Json.createWriter(out)) {
            w.writeObject(o);
        }
        return out.toByteArray();
    }
}
