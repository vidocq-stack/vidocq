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
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** MP JWT 2.2: without a configured algorithm both RSA and EC PEM keys must load. */
class PemKeysTest {

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    void autoDetectsRsaPem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair rsa = g.generateKeyPair();
        assertInstanceOf(RSAPublicKey.class, PemKeys.fromPem(pem(rsa.getPublic())));
    }

    @Test
    void autoDetectsEcPem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ec = g.generateKeyPair();
        assertInstanceOf(ECPublicKey.class, PemKeys.fromPem(pem(ec.getPublic())));
    }

    @Test
    void explicitFamilyStillRejectsMismatch() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        String ecPem = pem(g.generateKeyPair().getPublic());
        assertThrows(JwtValidationException.class,
                () -> PemKeys.fromPem(ecPem, SignatureAlgorithm.Family.RSA));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(JwtValidationException.class,
                () -> PemKeys.fromPem("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"));
    }
}
