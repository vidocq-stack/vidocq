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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JOSE {@code R‖S} ⇄ DER transcoding of ECDSA signatures (RFC 7518 §3.4) on the three JWS curves.
 */
class EcdsaSignaturesTest {

    private static final byte[] DATA = "header.payload".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @ValueSource(strings = {"secp256r1:SHA256withECDSA:32", "secp384r1:SHA384withECDSA:48", "secp521r1:SHA512withECDSA:66"})
    void jcaSignature_roundTripsThroughJose(String spec) throws Exception {
        String[] parts = spec.split(":");
        int n = Integer.parseInt(parts[2]);
        KeyPair pair = TestJwts.ecKeyPair(parts[0]);
        Signature signer = Signature.getInstance(parts[1]);
        signer.initSign(pair.getPrivate());
        signer.update(DATA);
        byte[] der = signer.sign();

        byte[] jose = EcdsaSignatures.derToJose(der, n);
        assertEquals(2 * n, jose.length);

        Signature verifier = Signature.getInstance(parts[1]);
        verifier.initVerify(pair.getPublic());
        verifier.update(DATA);
        assertTrue(verifier.verify(EcdsaSignatures.joseToDer(jose, n)));
    }

    @Test
    void leadingZerosAreTrimmedAndHighBitsArePadded() {
        byte[] jose = new byte[64];
        jose[31] = 0x01;                  // r = 1
        Arrays.fill(jose, 32, 64, (byte) 0xff); // s has its high bit set

        byte[] der = EcdsaSignatures.joseToDer(jose, 32);

        assertEquals(0x30, der[0]);
        assertArrayEquals(new byte[]{0x02, 0x01, 0x01}, Arrays.copyOfRange(der, 2, 5));
        assertEquals(0x21, der[6]);      // 33 bytes: 0x00 sign pad + 32 bytes
        assertEquals(0x00, der[7]);
        assertArrayEquals(jose, EcdsaSignatures.derToJose(der, 32));
    }

    @Test
    void wrongJoseLength_isRejected() {
        byte[] jose = new byte[63];
        assertThrows(IllegalArgumentException.class, () -> EcdsaSignatures.joseToDer(jose, 32));
    }

    @Test
    void twoByteDerLength_isAccepted() {
        byte[] der = {0x30, (byte) 0x82, 0x00, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x07};
        byte[] jose = EcdsaSignatures.derToJose(der, 2);
        assertArrayEquals(new byte[]{0, 5, 0, 7}, jose);
    }

    @Test
    void malformedDer_isRejected() {
        byte[] notSequence = {0x31, 0x00};
        byte[] longLength = {0x30, (byte) 0x83, 0, 0, 0};
        byte[] notInteger = {0x30, 0x03, 0x04, 0x01, 0x01};
        byte[] tooLarge = {0x30, 0x06, 0x02, 0x02, 0x01, 0x01, 0x02, 0x00};
        assertThrows(IllegalArgumentException.class, () -> EcdsaSignatures.derToJose(notSequence, 1));
        assertThrows(IllegalArgumentException.class, () -> EcdsaSignatures.derToJose(longLength, 1));
        assertThrows(IllegalArgumentException.class, () -> EcdsaSignatures.derToJose(notInteger, 1));
        assertThrows(IllegalArgumentException.class, () -> EcdsaSignatures.derToJose(tooLarge, 1));
    }
}
