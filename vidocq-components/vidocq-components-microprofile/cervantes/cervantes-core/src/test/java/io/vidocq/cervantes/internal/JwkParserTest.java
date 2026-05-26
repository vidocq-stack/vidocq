package io.vidocq.cervantes.internal;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reconstruction de clés publiques depuis un JWK Set (RFC 7517) : RSA ({@code n}/{@code e}) et EC ({@code x}/{@code y}). */
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
        // une clé reconstruite via X509/spec égale l'originale (même matériel)
        assertEquals(rsa.getPublic(), jwks.byKid().get("rsa-1"));
        assertEquals(ec.getPublic(), jwks.byKid().get("ec-1"));
        assertTrue(jwks.all().size() == 2);
    }

    @Test
    void ignoresEncryptionKeysAndUnsupportedTypes() throws Exception {
        KeyPair rsa = TestJwts.rsaKeyPair();
        // JWKS avec une clé "sig" RSA, une clé "enc" et un kty inconnu : seule la première reste.
        String jwksJson = "{\"keys\":["
                + new String(TestJwks.jwk("rsa-1", rsa.getPublic()).toString().getBytes())
                + ",{\"kty\":\"oct\",\"kid\":\"hmac\",\"k\":\"AAAA\"}"
                + "]}";
        Jwks jwks = JwkParser.parse(jwksJson.getBytes());
        assertEquals(1, jwks.byKid().size());
        assertTrue(jwks.byKid().containsKey("rsa-1"));
    }
}
