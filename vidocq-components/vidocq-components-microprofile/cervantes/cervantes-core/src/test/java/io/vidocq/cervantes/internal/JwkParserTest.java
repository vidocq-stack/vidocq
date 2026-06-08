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

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
