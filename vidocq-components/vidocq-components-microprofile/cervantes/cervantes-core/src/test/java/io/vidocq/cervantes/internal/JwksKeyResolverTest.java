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
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWKS key resolution tests (MicroProfile JWT 2.2 §9; RFC 7517): validation
 * end-to-end, cache, rotation by {@code kid}, unknown kid, EC key.
 */
class JwksKeyResolverTest {

    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://issuer.vidocq.dev";

    private static KeyPair RSA_A;
    private static KeyPair RSA_B;
    private static KeyPair EC;

    @BeforeAll
    static void keys() throws Exception {
        RSA_A = TestJwts.rsaKeyPair();
        RSA_B = TestJwts.rsaKeyPair();
        EC = TestJwts.ecKeyPair("secp256r1");
    }

    private static JsonObject claims() {
        return Json.createObjectBuilder()
                .add("iss", ISS)
                .add("sub", "u-1")
                .add("exp", NOW.getEpochSecond() + 3600)
                .build();
    }

    private static String token(String kid, KeyPair kp, SignatureAlgorithm alg) throws Exception {
        return TestJwts.sign(TestJwts.header(alg, kid), claims(), kp.getPrivate(), alg);
    }

    private static DefaultJwtValidator validator(io.vidocq.cervantes.api.KeyResolver resolver) {
        return new DefaultJwtValidator(resolver, JwtConfig.forIssuer(ISS), CLOCK);
    }

    @Test
    void validatesTokenWhoseKeyIsInTheJwks() throws Exception {
        byte[] jwks = TestJwks.jwksJson("rsa-a", RSA_A.getPublic());
        JwksKeyResolver resolver = new JwksKeyResolver(() -> jwks);

        JsonWebToken jwt = validator(resolver).validate(token("rsa-a", RSA_A, SignatureAlgorithm.RS256));
        assertEquals("u-1", jwt.getSubject());
    }

    @Test
    void resolvesEcKeyFromJwks() throws Exception {
        byte[] jwks = TestJwks.jwksJson("ec-1", EC.getPublic());
        JwksKeyResolver resolver = new JwksKeyResolver(() -> jwks);

        JsonWebToken jwt = validator(resolver).validate(token("ec-1", EC, SignatureAlgorithm.ES256));
        assertEquals("u-1", jwt.getSubject());
    }

    @Test
    void cachesWithinRefreshInterval() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        byte[] jwks = TestJwks.jwksJson("rsa-a", RSA_A.getPublic());
        JwksSource counting = () -> {
            fetches.incrementAndGet();
            return jwks;
        };
        JwksKeyResolver resolver = new JwksKeyResolver(
                counting, Duration.ofMinutes(5), Duration.ofSeconds(15), CLOCK);

        assertTrue(resolver.resolve("rsa-a", SignatureAlgorithm.RS256).isPresent());
        assertTrue(resolver.resolve("rsa-a", SignatureAlgorithm.RS256).isPresent());
        assertEquals(1, fetches.get(), "known kid within TTL must not re-fetch");
    }

    @Test
    void refreshesOnUnknownKid_pickingUpRotatedKey() throws Exception {
        //Mutable source: first only key A, then A + B (rotation).
        Map<String, PublicKey> set = new LinkedHashMap<>();
        set.put("rsa-a", RSA_A.getPublic());
        AtomicReference<byte[]> body = new AtomicReference<>(TestJwks.jwksJson(set));
        JwksSource mutable = () -> body.get();

        //minRefreshInterval = 0 → An unknown kid immediately triggers a refresh.
        JwksKeyResolver resolver = new JwksKeyResolver(
                mutable, Duration.ofMinutes(5), Duration.ZERO, CLOCK);

        //kid b absent from set → not found
        assertEquals(Optional.empty(), resolver.resolve("rsa-b", SignatureAlgorithm.RS256));

        //rotation: the new key B is published
        set.put("rsa-b", RSA_B.getPublic());
        body.set(TestJwks.jwksJson(set));

        assertTrue(resolver.resolve("rsa-b", SignatureAlgorithm.RS256).isPresent(),
                "unknown kid must trigger a refresh that discovers the rotated key");
    }

    @Test
    void keepsStaleSnapshotWhenRefreshFails() throws Exception {
        AtomicReference<JwksSource> delegate =
                new AtomicReference<>(() -> TestJwks.jwksJson("rsa-a", RSA_A.getPublic()));
        JwksSource flaky = () -> delegate.get().fetch();
        JwksKeyResolver resolver = new JwksKeyResolver(
                flaky, Duration.ZERO, Duration.ZERO, CLOCK); //TTL 0 → refresh every call

        assertTrue(resolver.resolve("rsa-a", SignatureAlgorithm.RS256).isPresent());

        // the source goes down: the previous snapshot must be kept
        delegate.set(() -> { throw new JwtValidationException("network down"); });
        assertTrue(resolver.resolve("rsa-a", SignatureAlgorithm.RS256).isPresent(),
                "a transient fetch failure must fall back to the cached snapshot");
    }

    @Test
    void propagatesFailureOnInitialLoad() {
        JwksKeyResolver resolver = new JwksKeyResolver(() -> {
            throw new JwtValidationException("unreachable");
        });
        assertThrows(JwtValidationException.class,
                () -> resolver.resolve("any", SignatureAlgorithm.RS256));
    }

    @Test
    void usesSingleKeyWhenTokenHasNoKid() throws Exception {
        byte[] jwks = TestJwks.jwksJson("rsa-a", RSA_A.getPublic());
        JwksKeyResolver resolver = new JwksKeyResolver(() -> jwks);

        //kid null + single key set → key used
        Optional<PublicKey> key = resolver.resolve(null, SignatureAlgorithm.RS256);
        assertNotNull(key.orElse(null));
    }

    @Test
    void mixedRsaAndEcJwksYieldsTheRightKeyPerAlgorithm() throws Exception {
        Map<String, PublicKey> set = new LinkedHashMap<>();
        set.put("rskey", RSA_A.getPublic());
        set.put("eckey", EC.getPublic());
        byte[] jwks = TestJwks.jwksJson(set);
        DefaultJwtValidator validator = validator(new JwksKeyResolver(() -> jwks));

        assertEquals("u-1", validator.validate(token("rskey", RSA_A, SignatureAlgorithm.RS256)).getSubject());
        assertEquals("u-1", validator.validate(token("eckey", EC, SignatureAlgorithm.ES256)).getSubject());
    }
}
