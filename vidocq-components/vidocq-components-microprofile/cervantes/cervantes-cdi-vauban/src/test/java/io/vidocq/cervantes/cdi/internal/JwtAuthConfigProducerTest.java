package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.cdi.CdiTestSupport;
import io.vidocq.cervantes.internal.DefaultJwtValidator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Construction de la config JWT et du KeyResolver depuis MicroProfile Config (mp.jwt.verify.*). */
class JwtAuthConfigProducerTest {

    @Test
    void buildsJwtConfigFromMpProperties() {
        Config config = CdiTestSupport.config(Map.of(
                "mp.jwt.verify.issuer", "https://issuer.vidocq.dev",
                "mp.jwt.verify.audiences", "svc-a, svc-b"));

        JwtConfig jwtConfig = JwtAuthConfigProducer.buildConfig(config);

        assertEquals("https://issuer.vidocq.dev", jwtConfig.issuer().orElseThrow());
        assertEquals(Set.of("svc-a", "svc-b"), jwtConfig.audiences());
    }

    @Test
    void inlinePublicKey_resolvesAndValidatesToken() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        Config config = CdiTestSupport.config(Map.of(
                "mp.jwt.verify.issuer", "https://issuer.vidocq.dev",
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic())));

        KeyResolver resolver = JwtAuthConfigProducer.buildKeyResolver(config);
        JwtValidator validator = new DefaultJwtValidator(resolver, JwtAuthConfigProducer.buildConfig(config));

        JsonObject claims = Json.createObjectBuilder()
                .add("iss", "https://issuer.vidocq.dev")
                .add("sub", "u-42")
                .add("exp", Instant.now().getEpochSecond() + 3600)
                .build();
        JsonWebToken jwt = validator.validate(CdiTestSupport.signRs256(claims, rsa.getPrivate()));

        assertEquals("u-42", jwt.getSubject());
    }

    @Test
    void missingKey_isRejected() {
        Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.issuer", "x"));
        assertThrows(JwtValidationException.class, () -> JwtAuthConfigProducer.buildKeyResolver(config));
    }

    @Test
    void decryptKey_buildsDecryptorWhenConfigured() throws Exception {
        KeyPair enc = CdiTestSupport.rsaKeyPair();
        String privateKeyB64 = Base64.getEncoder().encodeToString(enc.getPrivate().getEncoded());

        assertNotNull(JwtAuthConfigProducer.buildDecryptor(
                CdiTestSupport.config(Map.of("mp.jwt.decrypt.key", privateKeyB64))));
        assertNull(JwtAuthConfigProducer.buildDecryptor(CdiTestSupport.config(Map.of())),
                "no decryption key configured → no decryptor");
    }

    @Test
    void noAudiences_yieldsEmptySet() {
        JwtConfig jwtConfig = JwtAuthConfigProducer.buildConfig(CdiTestSupport.config(Map.of()));
        assertTrue(jwtConfig.audiences().isEmpty());
        assertTrue(jwtConfig.issuer().isEmpty());
    }
}
