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
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reconstructs public keys from a JWK Set (RFC 7517): RSA ({@code n}/{@code e}) and EC ({@code x}/{@code y}). */
class JwkParserTest {

    @Test
    void reconstructsRsaAndEcKeysByKid() throws Exception {
        KeyPair rsa = TestJwts.rsaKeyPair();
        KeyPair ec = TestJwts.ecKeyPair("secp256r1");

        Map<String, java.security.PublicKey> set = new LinkedHashMap<>();
        set.put("rsa-1", rsa.getPublic());
        set.put("ec-1", ec.getPublic());

        Jwks jwks = JwkParser.parse(TestJwks.jwksJson(set));

        assertEquals(2, jwks.byKid().size());
        //a key rebuilt via X509/spec equals the original (same hardware)
        assertEquals(rsa.getPublic(), jwks.byKid().get("rsa-1"));
        assertEquals(ec.getPublic(), jwks.byKid().get("ec-1"));
        assertTrue(jwks.all().size() == 2);
    }

    @Test
    void ignoresEncryptionKeysAndUnsupportedTypes() throws Exception {
        KeyPair rsa = TestJwts.rsaKeyPair();
        //JWKS with a "sig" RSA key, an "enc" key and an unknown kty: only the first left.
        String jwksJson = "{\"keys\":["
                + new String(TestJwks.jwk("rsa-1", rsa.getPublic()).toString().getBytes())
                + ",{\"kty\":\"oct\",\"kid\":\"hmac\",\"k\":\"AAAA\"}"
                + "]}";
        Jwks jwks = JwkParser.parse(jwksJson.getBytes());
        assertEquals(1, jwks.byKid().size());
        assertTrue(jwks.byKid().containsKey("rsa-1"));
    }

    // --- public keys: edge cases (RFC 7517 §4, §5) ---------------------------

    private static final String KTY = "kty";
    private static final String RSA_KTY = "RSA";
    private static final String KEYS = "keys";

    private static byte[] bytes(JsonObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void invalidJson_isRejected() {
        byte[] json = "{not json".getBytes(StandardCharsets.UTF_8);
        assertThrows(JwtValidationException.class, () -> JwkParser.parse(json));
    }

    @Test
    void singleJwkWithoutKid_isListedButNotIndexed() throws Exception {
        KeyPair rsa = TestJwts.rsaKeyPair();
        JsonObject jwk = Json.createObjectBuilder(TestJwks.jwk("ignored", rsa.getPublic())).remove("kid").build();

        Jwks jwks = JwkParser.parse(bytes(jwk));

        assertTrue(jwks.byKid().isEmpty());
        assertEquals(1, jwks.all().size());
        assertEquals(rsa.getPublic(), jwks.all().getFirst());
    }

    @Test
    void ignoresNonObjectEntriesEncryptionKeysAndUnreadableKeys() throws Exception {
        KeyPair rsa = TestJwts.rsaKeyPair();
        JsonObject sig = TestJwks.jwk("sig", rsa.getPublic());
        JsonObject root = Json.createObjectBuilder().add(KEYS, Json.createArrayBuilder()
                .add("not-a-jwk")
                .add(Json.createObjectBuilder(sig).add("kid", "enc").add("use", "enc"))
                .add(Json.createObjectBuilder().add("kid", "no-kty"))
                .add(Json.createObjectBuilder().add(KTY, RSA_KTY).add("kid", "no-modulus").add("e", "AQAB"))
                .add(Json.createObjectBuilder().add(KTY, "EC").add("kid", "bad-curve").add("crv", "P-192"))
                .add(Json.createObjectBuilder().add(KTY, "EC").add("kid", "no-curve"))
                .add(sig)).build();

        Jwks jwks = JwkParser.parse(bytes(root));

        assertEquals(Map.of("sig", rsa.getPublic()), jwks.byKid());
        assertEquals(1, jwks.all().size());
    }

    @Test
    void keysMemberThatIsNotAnArray_isReadAsASingleJwk() throws Exception {
        JsonObject root = Json.createObjectBuilder().add(KEYS, "nope").build();
        Jwks jwks = JwkParser.parse(bytes(root));
        assertTrue(jwks.all().isEmpty());
    }

    @Test
    void reconstructsP384AndP521Keys() throws Exception {
        KeyPair p384 = TestJwts.ecKeyPair("secp384r1");
        KeyPair p521 = TestJwts.ecKeyPair("secp521r1");
        Map<String, java.security.PublicKey> set = new LinkedHashMap<>();
        set.put("p384", p384.getPublic());
        set.put("p521", p521.getPublic());

        Jwks jwks = JwkParser.parse(TestJwks.jwksJson(set));

        assertEquals(p384.getPublic(), jwks.byKid().get("p384"));
        assertEquals(p521.getPublic(), jwks.byKid().get("p521"));
    }

    // --- private keys (mp.jwt.decrypt.key.location as a JWK) ------------------

    private static JsonObjectBuilder rsaPrivateJwk(RSAPrivateCrtKey k, boolean withCrt) {
        JsonObjectBuilder b = Json.createObjectBuilder().add(KTY, RSA_KTY)
                .add("n", b64(k.getModulus())).add("e", b64(k.getPublicExponent()))
                .add("d", b64(k.getPrivateExponent()));
        if (withCrt) {
            b.add("p", b64(k.getPrimeP())).add("q", b64(k.getPrimeQ()))
                    .add("dp", b64(k.getPrimeExponentP())).add("dq", b64(k.getPrimeExponentQ()))
                    .add("qi", b64(k.getCrtCoefficient()));
        }
        return b;
    }

    @Test
    void parsesSingleRsaPrivateJwkWithCrtParameters() throws Exception {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) TestJwts.rsaKeyPair().getPrivate();

        PrivateKey parsed = JwkParser.parsePrivateKey(bytes(rsaPrivateJwk(key, true).build()));

        RSAPrivateCrtKey crt = assertInstanceOf(RSAPrivateCrtKey.class, parsed);
        assertEquals(key.getModulus(), crt.getModulus());
        assertEquals(key.getPrimeP(), crt.getPrimeP());
    }

    @Test
    void parsesRsaPrivateJwkWithoutCrtParameters() throws Exception {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) TestJwts.rsaKeyPair().getPrivate();

        PrivateKey parsed = JwkParser.parsePrivateKey(bytes(rsaPrivateJwk(key, false).build()));

        RSAPrivateKey rsa = assertInstanceOf(RSAPrivateKey.class, parsed);
        assertFalse(rsa instanceof RSAPrivateCrtKey);
        assertEquals(key.getPrivateExponent(), rsa.getPrivateExponent());
    }

    @Test
    void picksTheFirstUsableRsaPrivateKeyOfASet() throws Exception {
        KeyPair rsa = TestJwts.rsaKeyPair();
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) rsa.getPrivate();
        JsonObject root = Json.createObjectBuilder().add(KEYS, Json.createArrayBuilder()
                .add(42)
                .add(TestJwks.jwk("ec", TestJwts.ecKeyPair("secp256r1").getPublic()))
                .add(TestJwks.jwk("public-only", rsa.getPublic()))
                .add(Json.createObjectBuilder().add(KTY, RSA_KTY).add("d", "AQAB"))
                .add(rsaPrivateJwk(key, true))).build();

        RSAPrivateKey parsed = assertInstanceOf(RSAPrivateKey.class, JwkParser.parsePrivateKey(bytes(root)));

        assertEquals(key.getPrivateExponent(), parsed.getPrivateExponent());
    }

    @Test
    void privateKeyDocumentWithoutRsaPrivateKey_isRejected() throws Exception {
        byte[] json = TestJwks.jwksJson("rsa", TestJwts.rsaKeyPair().getPublic());
        JwtValidationException ex = assertThrows(JwtValidationException.class, () -> JwkParser.parsePrivateKey(json));
        assertTrue(ex.getMessage().contains("no RSA private key"));
    }

    @Test
    void invalidPrivateKeyJson_isRejected() {
        byte[] json = "[]".getBytes(StandardCharsets.UTF_8);
        assertThrows(JwtValidationException.class, () -> JwkParser.parsePrivateKey(json));
    }
}
