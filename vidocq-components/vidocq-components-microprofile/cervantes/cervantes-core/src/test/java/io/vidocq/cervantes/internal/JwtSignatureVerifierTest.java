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
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JwtSignatureVerifier} reuses its {@link Signature} objects instead of asking the JCA for one per token. */
class JwtSignatureVerifierTest {

    private static final JwtParser PARSER = new JwtParser();

    private static String token(KeyPair pair, SignatureAlgorithm alg) throws Exception {
        var claims = Json.createObjectBuilder().add("sub", "alice").build();
        return TestJwts.sign(TestJwts.header(alg, null), claims, pair.getPrivate(), alg);
    }

    /** The token with one character of the signature, well inside the base64url, replaced. */
    private static String tampered(String token) {
        int at = token.lastIndexOf('.') + 5;
        char flipped = token.charAt(at) == 'A' ? 'B' : 'A';
        return token.substring(0, at) + flipped + token.substring(at + 1);
    }

    @Test
    void sequentialVerifications_reuseOneSignaturePerAlgorithm() throws Exception {
        var created = new AtomicInteger();
        var verifier = new JwtSignatureVerifier(name -> {
            created.incrementAndGet();
            return Signature.getInstance(name);
        });
        KeyPair rsa = TestJwts.rsaKeyPair();
        ParsedJwt jwt = PARSER.parse(token(rsa, SignatureAlgorithm.RS256));

        for (int i = 0; i < 5; i++) {
            assertTrue(verifier.verify(jwt, rsa.getPublic(), SignatureAlgorithm.RS256));
        }

        assertEquals(1, created.get());
    }

    @Test
    void aReusedSignature_isReinitialisedWithTheNextKey() throws Exception {
        var verifier = new JwtSignatureVerifier();
        KeyPair first = TestJwts.rsaKeyPair();
        KeyPair second = TestJwts.rsaKeyPair();
        ParsedJwt jwt = PARSER.parse(token(first, SignatureAlgorithm.RS256));

        assertTrue(verifier.verify(jwt, first.getPublic(), SignatureAlgorithm.RS256));
        assertFalse(verifier.verify(jwt, second.getPublic(), SignatureAlgorithm.RS256));
        assertTrue(verifier.verify(jwt, first.getPublic(), SignatureAlgorithm.RS256));
    }

    @Test
    void aRejectedKey_doesNotPoisonLaterVerifications() throws Exception {
        var verifier = new JwtSignatureVerifier();
        KeyPair rsa = TestJwts.rsaKeyPair();
        KeyPair ec = TestJwts.ecKeyPair("secp256r1");
        ParsedJwt jwt = PARSER.parse(token(rsa, SignatureAlgorithm.RS256));

        assertThrows(JwtValidationException.class, () -> verifier.verify(jwt, ec.getPublic(), SignatureAlgorithm.RS256));

        assertTrue(verifier.verify(jwt, rsa.getPublic(), SignatureAlgorithm.RS256));
    }

    @Test
    void ecdsaVerifications_stayCorrectWhenReused() throws Exception {
        var verifier = new JwtSignatureVerifier();
        KeyPair ec = TestJwts.ecKeyPair("secp256r1");
        String token = token(ec, SignatureAlgorithm.ES256);

        for (int i = 0; i < 3; i++) {
            assertTrue(verifier.verify(PARSER.parse(token), ec.getPublic(), SignatureAlgorithm.ES256));
            assertFalse(verifier.verify(PARSER.parse(tampered(token)), ec.getPublic(), SignatureAlgorithm.ES256));
        }
    }

    @Test
    void concurrentVerifications_giveTheSameVerdictsAsSequentialOnes() throws Exception {
        var verifier = new JwtSignatureVerifier();
        KeyPair rsa = TestJwts.rsaKeyPair();
        String valid = token(rsa, SignatureAlgorithm.RS256);
        ParsedJwt good = PARSER.parse(valid);
        ParsedJwt bad = PARSER.parse(tampered(valid));

        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            boolean expectValid = i % 2 == 0;
            jobs.add(() -> verifier.verify(expectValid ? good : bad, rsa.getPublic(), SignatureAlgorithm.RS256) == expectValid);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : executor.invokeAll(jobs)) {
                assertTrue(result.get());
            }
        }
    }
}
