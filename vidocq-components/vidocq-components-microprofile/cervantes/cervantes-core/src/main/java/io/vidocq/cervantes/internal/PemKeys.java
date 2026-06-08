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

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Load a public key from a {@code SubjectPublicKeyInfo} PEM ({@code -----BEGIN PUBLIC KEY-----})
 * via the JCA — without third-party cryptic dependence. RSA and EC cover (X.509 {@link X509EncodedKeySpec}).
 */
public final class PemKeys {

    private PemKeys() {}

    public static PublicKey fromPem(String pem, SignatureAlgorithm.Family family) throws JwtValidationException {
        String base64 = pem
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            String algorithm = switch (family) {
                case RSA -> "RSA";
                case EC -> "EC";
            };
            return KeyFactory.getInstance(algorithm).generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new JwtValidationException("invalid PEM public key", e);
        }
    }

    /**
     * Loads RSA private key from PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}) — key to
     * JWE decryption ({@code mp.jwt.decrypt.key} / {@code.location}).
     */
    public static PrivateKey privateKeyFromPem(String pem) throws JwtValidationException {
        String base64 = pem
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new JwtValidationException("invalid PEM private key", e);
        }
    }
}
