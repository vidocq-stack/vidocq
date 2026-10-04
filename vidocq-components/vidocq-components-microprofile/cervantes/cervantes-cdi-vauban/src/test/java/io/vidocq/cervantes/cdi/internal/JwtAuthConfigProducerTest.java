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
package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import io.vidocq.cervantes.cdi.CdiTestSupport;
import io.vidocq.cervantes.internal.DefaultJwtValidator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
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
    void buildsJwtConfigFromMpProperties() throws Exception {
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
    void noAudiences_yieldsEmptySet() throws Exception {
        JwtConfig jwtConfig = JwtAuthConfigProducer.buildConfig(CdiTestSupport.config(Map.of()));
        assertTrue(jwtConfig.audiences().isEmpty());
        assertTrue(jwtConfig.issuer().isEmpty());
    }

    @Test
    void noAlgorithmMeansBothFamiliesAccepted() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        String ecPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(g.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.publickey", ecPem));

        KeyResolver resolver = JwtAuthConfigProducer.buildKeyResolver(config);

        assertTrue(resolver.resolve(null, SignatureAlgorithm.ES256).isPresent());
        assertTrue(JwtAuthConfigProducer.buildConfig(config).requiredAlgorithm().isEmpty());
    }

    @Test
    void configuredAlgorithmIsExposedOnJwtConfig() throws Exception {
        Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.publickey.algorithm", "ES256"));
        assertEquals(SignatureAlgorithm.ES256,
                JwtAuthConfigProducer.buildConfig(config).requiredAlgorithm().orElseThrow());
    }

    @Test
    void unrecognisedAlgorithmFailsInBuildConfig() throws Exception {
        for (String bad : new String[] {"es256", "ES-256", "PS256", "HS256"}) {
            Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.publickey.algorithm", bad));
            JwtValidationException ex = assertThrows(JwtValidationException.class,
                    () -> JwtAuthConfigProducer.buildConfig(config), bad);
            assertTrue(ex.getMessage().contains("mp.jwt.verify.publickey.algorithm"), ex.getMessage());
            assertTrue(ex.getMessage().contains("'" + bad + "'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("RS256") && ex.getMessage().contains("ES512"), ex.getMessage());
        }
    }

    @Test
    void unrecognisedAlgorithmFailsInBuildKeyResolver() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        Config config = CdiTestSupport.config(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic()),
                "mp.jwt.verify.publickey.algorithm", "es256"));
        assertThrows(JwtValidationException.class, () -> JwtAuthConfigProducer.buildKeyResolver(config));
    }

    @Test
    void ecPemWithRsaAlgorithmIsRejected() throws Exception {
        Config config = CdiTestSupport.config(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.ecPublicKeyPem(),
                "mp.jwt.verify.publickey.algorithm", "RS256"));
        assertThrows(JwtValidationException.class, () -> JwtAuthConfigProducer.buildKeyResolver(config));
        assertThrows(IllegalStateException.class, () -> JwtAuthConfigProducer.createValidator(config));
    }

    @Test
    void createValidatorIsNullWhenNoKeyIsConfigured() {
        assertNull(JwtAuthConfigProducer.createValidator(
                CdiTestSupport.config(Map.of("mp.jwt.verify.publickey.algorithm", "bogus"))));
    }

    @Test
    void recognisedAlgorithmsStillAccepted() throws Exception {
        for (SignatureAlgorithm alg : SignatureAlgorithm.values()) {
            Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.publickey.algorithm", alg.name()));
            assertEquals(alg, JwtAuthConfigProducer.buildConfig(config).requiredAlgorithm().orElseThrow());
        }
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        Config config = CdiTestSupport.config(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic()),
                "mp.jwt.verify.publickey.algorithm", "RS256"));
        assertNotNull(JwtAuthConfigProducer.buildKeyResolver(config));
    }
}
