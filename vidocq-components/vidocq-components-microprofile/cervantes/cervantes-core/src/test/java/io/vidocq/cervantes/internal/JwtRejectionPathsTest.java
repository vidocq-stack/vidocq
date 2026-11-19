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

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejection paths of the JWS pipeline (RFC 7515 §5.2 validation steps, MicroProfile JWT 2.2 §9): compact
 * shape, base64url and JSON of each segment, {@code alg} header, key resolution and key family,
 * encryption requirement, and the JWKS cache behaviour on refresh failures (RFC 7517).
 */
class JwtRejectionPathsTest {

    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://issuer.vidocq.dev";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private static KeyPair rsa;
    private static KeyPair ec;

    @BeforeAll
    static void keys() throws Exception {
        rsa = TestJwts.rsaKeyPair();
        ec = TestJwts.ecKeyPair("secp256r1");
    }

    private static JsonObject claims(Instant now) {
        return Json.createObjectBuilder().add("iss", ISS).add("sub", "alice")
                .add("exp", now.getEpochSecond() + 3600).build();
    }

    private static String b64(String json) {
        return B64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultJwtValidator validator(KeyResolver resolver, JwtConfig config) {
        return new DefaultJwtValidator(resolver, config, CLOCK, null);
    }

    private static String rejection(DefaultJwtValidator validator, String token) {
        return assertThrows(JwtValidationException.class, () -> validator.validate(token)).getMessage();
    }

    private static DefaultJwtValidator rsaValidator() {
        return validator(new ConfiguredKeyResolver(rsa.getPublic()), JwtConfig.forIssuer(ISS));
    }

    @Test
    void twoArgumentConstructor_usesTheSystemClock() throws Exception {
        String token = TestJwts.sign(TestJwts.header(SignatureAlgorithm.RS256, null), claims(Instant.now()),
                rsa.getPrivate(), SignatureAlgorithm.RS256);
        DefaultJwtValidator validator = new DefaultJwtValidator(new ConfiguredKeyResolver(rsa.getPublic()),
                JwtConfig.forIssuer(ISS));
        assertEquals("alice", validator.validate(token).getSubject());
    }

    @Test
    void malformedCompactTokens_areRejected() {
        DefaultJwtValidator validator = rsaValidator();
        String header = b64("{\"alg\":\"RS256\"}");
        assertTrue(rejection(validator, null).contains("empty token"));
        assertTrue(rejection(validator, "   ").contains("empty token"));
        assertTrue(rejection(validator, "a.b").contains("3 dot-separated parts"));
        assertTrue(rejection(validator, header + ".e30.").contains("unsigned"));
        assertTrue(rejection(validator, "@@@.e30.c2ln").contains("base64url in JWT header"));
        assertTrue(rejection(validator, header + "." + b64("[1") + ".c2ln").contains("JSON in JWT payload"));
    }

    @Test
    void missingAlgHeader_isRejected() throws Exception {
        JsonObject header = Json.createObjectBuilder().add("typ", "JWT").build();
        String token = TestJwts.sign(header, claims(NOW), rsa.getPrivate(), SignatureAlgorithm.RS256);
        assertTrue(rejection(rsaValidator(), token).contains("'alg'"));
    }

    @Test
    void unresolvableKey_isRejected() throws Exception {
        String token = TestJwts.sign(TestJwts.header(SignatureAlgorithm.RS256, "unknown"), claims(NOW),
                rsa.getPrivate(), SignatureAlgorithm.RS256);
        DefaultJwtValidator validator = validator((kid, alg) -> Optional.empty(), JwtConfig.forIssuer(ISS));
        assertTrue(rejection(validator, token).contains("kid=unknown"));
    }

    @Test
    void keyOfTheWrongFamily_isRejected() throws Exception {
        String token = TestJwts.sign(TestJwts.header(SignatureAlgorithm.ES256, null), claims(NOW),
                ec.getPrivate(), SignatureAlgorithm.ES256);
        assertTrue(rejection(rsaValidator(), token).contains("does not match algorithm ES256"));
    }

    @Test
    void ecdsaSignatureOfTheWrongLength_isAnInvalidSignature() throws Exception {
        String token = TestJwts.sign(TestJwts.header(SignatureAlgorithm.ES256, null), claims(NOW),
                ec.getPrivate(), SignatureAlgorithm.ES256);
        String truncated = token.substring(0, token.lastIndexOf('.') + 1) + B64URL.encodeToString(new byte[10]);
        DefaultJwtValidator validator = validator(new ConfiguredKeyResolver(ec.getPublic()), JwtConfig.forIssuer(ISS));
        assertEquals("invalid signature", rejection(validator, truncated));
    }

    @Test
    void signedOnlyToken_isRejectedWhenEncryptionIsRequired() throws Exception {
        String token = TestJwts.sign(TestJwts.header(SignatureAlgorithm.RS256, null), claims(NOW),
                rsa.getPrivate(), SignatureAlgorithm.RS256);
        JwtConfig config = new JwtConfig(Optional.of(ISS), Set.of(), Duration.ofSeconds(60), true,
                Optional.empty(), true);
        assertTrue(rejection(validator(new ConfiguredKeyResolver(rsa.getPublic()), config), token).contains("JWE"));
    }

    // --- JWKS cache ------------------------------------------------------------------------------

    /** A clock the test moves forward. */
    private static final class MovingClock extends Clock {
        private Instant now = NOW;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void tokenWithoutKid_needsASingleKeySet() throws Exception {
        Map<String, java.security.PublicKey> two = new LinkedHashMap<>();
        two.put("a", rsa.getPublic());
        two.put("b", ec.getPublic());
        byte[] doc = TestJwks.jwksJson(two);
        JwksKeyResolver resolver = new JwksKeyResolver(() -> doc);
        assertTrue(resolver.resolve(null, SignatureAlgorithm.RS256).isEmpty());
        assertEquals(rsa.getPublic(), resolver.resolve("a", SignatureAlgorithm.RS256).orElseThrow());
    }

    @Test
    void failedRefresh_keepsTheLastGoodKeys() throws Exception {
        AtomicReference<byte[]> doc = new AtomicReference<>(TestJwks.jwksJson("a", rsa.getPublic()));
        MovingClock clock = new MovingClock();
        JwksKeyResolver resolver = new JwksKeyResolver(() -> {
            byte[] bytes = doc.get();
            if (bytes == null) throw new JwtValidationException("source down");
            return bytes;
        }, Duration.ofMinutes(5), Duration.ofSeconds(15), clock);

        assertNotNull(resolver.resolve("a", SignatureAlgorithm.RS256).orElseThrow());
        doc.set(null);
        clock.advance(Duration.ofMinutes(6));

        assertEquals(rsa.getPublic(), resolver.resolve("a", SignatureAlgorithm.RS256).orElseThrow());
        assertTrue(resolver.resolve("rotated", SignatureAlgorithm.RS256).isEmpty());
    }

    @Test
    void firstLoadFailure_isPropagated() {
        JwksKeyResolver resolver = new JwksKeyResolver(() -> {
            throw new JwtValidationException("source down");
        });
        assertThrows(JwtValidationException.class, () -> resolver.resolve("a", SignatureAlgorithm.RS256));
    }
}
