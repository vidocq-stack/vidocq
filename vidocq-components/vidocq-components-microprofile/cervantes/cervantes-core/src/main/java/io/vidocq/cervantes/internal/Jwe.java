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

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * Public factory for {@link JweDecryptor} from the JWT MicroProfile configuration
 * ({@code mp.jwt.decrypt.key} / {@code mp.jwt.decrypt.key.location}). Stable facade for
 * integrations: keep {@code JwksSource} (file/URL reading) internal to the core package.
 *
 * <p>Supports: PEM PKCS#8 ({@code -----BEGIN PRIVATE KEY-----}) and JWK/JWKS JSON (detected)
 * by the first character '{') for {@code mp.jwt.decrypt.key.location} pointing to a
 * RSA private key JWK file (MP JWT 2.2 spec §"JWE Private Key").</p>
 */
public final class Jwe {

    private Jwe() {}

    /**
     * Private inline decryption key (PEM PKCS#8 or JWK JSON), from {@code mp.jwt.decrypt.key}.
     * MP JWT spec §9.2.4: the value can be a PKCS#8 PEM or JWK JSON.
     *
     * @param value          raw key value (PEM or JWK JSON)
     * @param requiredAlgorithm optional {@code mp.jwt.decrypt.key.algorithm} value to enforce (null = no check)
     */
    public static JweDecryptor decryptorFromInlinePem(String value, String requiredAlgorithm) throws JwtValidationException {
        return new JweDecryptor(privateKeyFromValue(value.trim().getBytes(StandardCharsets.UTF_8)), requiredAlgorithm);
    }

    /** @deprecated Use {@link #decryptorFromInlinePem(String, String)} */
    @Deprecated
    public static JweDecryptor decryptorFromInlinePem(String value) throws JwtValidationException {
        return decryptorFromInlinePem(value, null);
    }

    /**
     * Private key from {@code mp.jwt.decrypt.key.location} (classpath file, URL, or FS path).
     * Supported formats: PEM PKCS#8, JWK JSON, JWKS JSON (first character '{').
     *
     * @param location       MP Config location value
     * @param requiredAlgorithm optional {@code mp.jwt.decrypt.key.algorithm} value to enforce (null = no check)
     */
    public static JweDecryptor decryptorFromLocation(String location, String requiredAlgorithm) throws JwtValidationException {
        byte[] bytes = JwksSource.fromLocation(location).fetch();
        return new JweDecryptor(privateKeyFromValue(bytes), requiredAlgorithm);
    }

    /** @deprecated Use {@link #decryptorFromLocation(String, String)} */
    @Deprecated
    public static JweDecryptor decryptorFromLocation(String location) throws JwtValidationException {
        return decryptorFromLocation(location, null);
    }

    private static PrivateKey privateKeyFromValue(byte[] bytes) throws JwtValidationException {
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.startsWith("{")) {
            //JWK or JWKS JSON — parse private key from JWK document
            return JwkParser.parsePrivateKey(bytes);
        }
        //PEM PKCS#8 — default format
        return PemKeys.privateKeyFromPem(content);
    }
}
