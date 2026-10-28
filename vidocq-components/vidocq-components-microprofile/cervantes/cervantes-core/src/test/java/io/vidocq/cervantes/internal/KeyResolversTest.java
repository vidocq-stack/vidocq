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
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Key resolvers built from {@code mp.jwt.verify.publickey} and {@code mp.jwt.verify.publickey.location}
 * (MicroProfile JWT 2.2 §9.2.1-9.2.2): PEM, JWK, JWK Set, base64-wrapped JWK(S), file and HTTP locations.
 */
class KeyResolversTest {

    private static final String LOCATION_PROPERTY = "mp.jwt.verify.publickey.location";
    private static final String KID = "k1";
    private static final String PEM_PATH = "/key.pem";
    private static final Optional<SignatureAlgorithm.Family> ANY = Optional.empty();

    private static KeyPair rsa;
    private static KeyPair ec;

    @TempDir
    Path dir;

    @BeforeAll
    static void keys() throws Exception {
        rsa = TestJwts.rsaKeyPair();
        ec = TestJwts.ecKeyPair("secp256r1");
    }

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static String jwks(PublicKey key) {
        return new String(TestJwks.jwksJson(KID, key), StandardCharsets.UTF_8);
    }

    private static PublicKey resolve(KeyResolver resolver, SignatureAlgorithm alg) throws JwtValidationException {
        return resolver.resolve(KID, alg).orElseThrow();
    }

    @Test
    void inlineJwkSet_isResolvedByKid() throws Exception {
        KeyResolver resolver = KeyResolvers.fromInlinePem("  " + jwks(rsa.getPublic()) + "\n", ANY);
        assertEquals(rsa.getPublic(), resolve(resolver, SignatureAlgorithm.RS256));
    }

    @Test
    void base64EncodedJwkSet_isDecoded() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(jwks(ec.getPublic()).getBytes(StandardCharsets.UTF_8));
        KeyResolver resolver = KeyResolvers.fromInlinePem(encoded, ANY);
        assertEquals(ec.getPublic(), resolve(resolver, SignatureAlgorithm.ES256));
    }

    @Test
    void pem_isParsedWithOrWithoutAFamily() throws Exception {
        String pem = pem(ec.getPublic());
        assertEquals(ec.getPublic(), resolve(KeyResolvers.fromInlinePem(pem, ANY), SignatureAlgorithm.ES256));
        assertEquals(ec.getPublic(), resolve(KeyResolvers.fromInlinePem(pem, Optional.of(SignatureAlgorithm.Family.EC)),
                SignatureAlgorithm.ES256));
    }

    @Test
    void bareBase64DerKey_isParsedAsPem() throws Exception {
        String der = Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded());
        assertEquals(rsa.getPublic(), resolve(KeyResolvers.fromInlinePem(der, ANY), SignatureAlgorithm.RS256));
    }

    @Test
    void textThatIsNeitherBase64NorPem_isRejected() {
        assertThrows(JwtValidationException.class, () -> KeyResolvers.fromInlinePem("not a key!", ANY));
    }

    @Test
    void fileLocation_withJwkSetOrPem() throws Exception {
        Path jwksFile = Files.writeString(dir.resolve("jwks.json"), jwks(rsa.getPublic()));
        Path pemFile = Files.writeString(dir.resolve("key.pem"), pem(rsa.getPublic()));

        KeyResolver fromJwks = KeyResolvers.fromLocation(jwksFile.toUri().toString(), ANY);
        KeyResolver fromPem = KeyResolvers.fromLocation(pemFile.toString(), Optional.of(SignatureAlgorithm.Family.RSA));

        assertEquals(rsa.getPublic(), resolve(fromJwks, SignatureAlgorithm.RS256));
        assertEquals(rsa.getPublic(), resolve(fromPem, SignatureAlgorithm.RS256));
    }

    @Test
    void httpLocation_isFetchedLazilyAndDetectsJwks() throws Exception {
        try (TestHttpServer server = new TestHttpServer(200, jwks(rsa.getPublic()))) {
            KeyResolver resolver = KeyResolvers.fromLocation(server.url("/jwks"), ANY);
            assertEquals(0, server.requests());

            assertEquals(rsa.getPublic(), resolve(resolver, SignatureAlgorithm.RS256));
            assertEquals(rsa.getPublic(), resolve(resolver, SignatureAlgorithm.RS256));
        }
    }

    @Test
    void httpLocation_servingPem_isDetected() throws Exception {
        try (TestHttpServer server = new TestHttpServer(200, pem(ec.getPublic()))) {
            KeyResolver resolver = KeyResolvers.fromLocation(server.url(PEM_PATH), ANY);

            assertEquals(ec.getPublic(), resolve(resolver, SignatureAlgorithm.ES256));
            assertEquals(1, server.requests());
            assertEquals(ec.getPublic(), resolve(resolver, SignatureAlgorithm.ES256));
            assertEquals(1, server.requests());
        }
    }

    @Test
    void httpLocation_isReReadFromTheSystemPropertyAtDetection() throws Exception {
        String previous = System.getProperty(LOCATION_PROPERTY);
        try (TestHttpServer stale = new TestHttpServer(500, "stale");
             TestHttpServer current = new TestHttpServer(200, pem(rsa.getPublic()))) {
            KeyResolver resolver = KeyResolvers.fromLocation(stale.url(PEM_PATH), ANY);
            System.setProperty(LOCATION_PROPERTY, current.url(PEM_PATH));

            assertEquals(rsa.getPublic(), resolve(resolver, SignatureAlgorithm.RS256));
            assertEquals(0, stale.requests());
        } finally {
            if (previous == null) {
                System.clearProperty(LOCATION_PROPERTY);
            } else {
                System.setProperty(LOCATION_PROPERTY, previous);
            }
        }
    }
}
