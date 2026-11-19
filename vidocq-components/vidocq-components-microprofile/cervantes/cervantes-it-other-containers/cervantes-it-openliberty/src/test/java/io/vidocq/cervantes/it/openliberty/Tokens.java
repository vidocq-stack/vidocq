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
package io.vidocq.cervantes.it.openliberty;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs RS256 tokens for the test. The server verifies them with {@code META-INF/test-verify-key.pem};
 * the matching private key, generated for this module only, is a test resource.
 */
final class Tokens {

    static final String ISSUER = "https://issuer.example.test";

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The private half of the key pair the server trusts. */
    static PrivateKey signingKey() {
        try (var in = Tokens.class.getResourceAsStream("/test-signing-key.pem")) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token for {@code upn}, member of {@code groups}, valid for five minutes. */
    static String sign(PrivateKey key, String upn, String... groups) {
        long now = Instant.now().getEpochSecond();
        StringBuilder groupArray = new StringBuilder("[");
        for (int i = 0; i < groups.length; i++) {
            groupArray.append(i == 0 ? "" : ",").append('"').append(groups[i]).append('"');
        }
        groupArray.append(']');
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"id-" + upn + "\",\"upn\":\"" + upn
                + "\",\"jti\":\"" + now + "-" + upn + "\",\"groups\":" + groupArray
                + ",\"iat\":" + now + ",\"exp\":" + (now + 300) + "}";
        String signingInput = encode(header) + "." + encode(payload);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
