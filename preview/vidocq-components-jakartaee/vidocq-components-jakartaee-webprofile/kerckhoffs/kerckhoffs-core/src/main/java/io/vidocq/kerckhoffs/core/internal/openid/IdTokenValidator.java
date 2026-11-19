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
package io.vidocq.kerckhoffs.core.internal.openid;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Validates an ID token as OpenID Connect Core 1.0 section 3.1.3.7 asks: the signature against the keys of the
 * provider, then the issuer, the audience, the expiry and the nonce. Anything that does not pass is refused.
 *
 * <p>Only RS256 is accepted: a token never chooses a weaker algorithm, none, or a symmetric one that would take the
 * public key for a secret.
 */
public final class IdTokenValidator {

    private static final String ALGORITHM = "RS256";

    private final JwkSet keys;
    private final String issuer;
    private final String clientId;
    private final Clock clock;
    private final Duration skew;

    public IdTokenValidator(JwkSet keys, String issuer, String clientId, Clock clock, Duration skew) {
        this.keys = keys;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clock = clock;
        this.skew = skew;
    }

    /**
     * @param token the compact serialization of the ID token
     * @param expectedNonce the nonce sent in the authentication request; the token must carry the same one
     * @return the claims of the token
     * @throws InvalidTokenException whenever the token does not pass
     */
    public JsonObject validate(String token, String expectedNonce) throws InvalidTokenException {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[2].isEmpty()) {
            throw new InvalidTokenException("The token is not a signed JWT");
        }
        try {
            JsonObject header = object(parts[0]);
            if (!ALGORITHM.equals(string(header, "alg"))) {
                throw new InvalidTokenException("The signature algorithm is not " + ALGORITHM);
            }
            PublicKey key = keys.find(string(header, "kid"));
            if (key == null) {
                throw new InvalidTokenException("No key of the provider matches the token");
            }
            verifySignature(parts, key);
            JsonObject claims = object(parts[1]);
            checkClaims(claims, expectedNonce);
            return claims;
        } catch (IllegalArgumentException | JsonException e) {
            throw new InvalidTokenException("The token cannot be read", e);
        }
    }

    private static void verifySignature(String[] parts, PublicKey key) throws InvalidTokenException {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidTokenException("The signature does not match");
            }
        } catch (GeneralSecurityException e) {
            throw new InvalidTokenException("The signature cannot be checked", e);
        }
    }

    private void checkClaims(JsonObject claims, String expectedNonce) throws InvalidTokenException {
        if (!issuer.equals(string(claims, "iss"))) {
            throw new InvalidTokenException("The issuer is not the provider");
        }
        checkAudience(claims);
        Instant now = clock.instant();
        Instant expiry = time(claims, "exp");
        if (expiry == null || now.isAfter(expiry.plus(skew))) {
            throw new InvalidTokenException("The token is expired or has no expiry");
        }
        Instant notBefore = time(claims, "nbf");
        if (notBefore != null && now.plus(skew).isBefore(notBefore)) {
            throw new InvalidTokenException("The token is not valid yet");
        }
        if (expectedNonce != null) {
            String nonce = string(claims, "nonce");
            if (nonce == null || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),
                    expectedNonce.getBytes(StandardCharsets.UTF_8))) {
                throw new InvalidTokenException("The nonce is not the one of the request");
            }
        }
    }

    private void checkAudience(JsonObject claims) throws InvalidTokenException {
        JsonValue audience = claims.get("aud");
        boolean listed = false;
        int count = 0;
        if (audience instanceof JsonString single) {
            count = 1;
            listed = clientId.equals(single.getString());
        } else if (audience instanceof JsonArray list) {
            count = list.size();
            for (JsonValue entry : list) {
                listed |= entry instanceof JsonString text && clientId.equals(text.getString());
            }
        }
        if (!listed) {
            throw new InvalidTokenException("The audience does not hold this client");
        }
        if (count > 1 && !clientId.equals(string(claims, "azp"))) {
            throw new InvalidTokenException("The token has several audiences and this client is not the authorized party");
        }
    }

    private static JsonObject object(String segment) {
        byte[] bytes = Base64.getUrlDecoder().decode(segment);
        try (JsonReader reader = Json.createReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            return reader.readObject();
        }
    }

    /** The value when it is a JSON string, {@code null} otherwise (absent or of another type). */
    private static String string(JsonObject object, String name) {
        return object.get(name) instanceof JsonString text ? text.getString() : null;
    }

    private static Instant time(JsonObject object, String name) {
        return object.get(name) instanceof JsonNumber number ? Instant.ofEpochSecond(number.longValue()) : null;
    }
}
