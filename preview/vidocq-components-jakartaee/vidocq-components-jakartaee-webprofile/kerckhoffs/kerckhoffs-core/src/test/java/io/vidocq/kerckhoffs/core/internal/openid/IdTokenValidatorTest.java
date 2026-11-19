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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;

/** The validation of an ID token (OpenID Connect Core 1.0 section 3.1.3.7): every refusal first, then the accepted token. */
class IdTokenValidatorTest {

    private static final String ISSUER = "https://idp.example/realm";
    private static final String CLIENT = "client-1";
    private static final String NONCE = "n-0S6_WzA2Mj";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private static KeyPair pair;
    private static KeyPair otherPair;

    private final IdTokenValidator validator = new IdTokenValidator(keys(), ISSUER, CLIENT,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        otherPair = generator.generateKeyPair();
    }

    private static JwkSet keys() {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        return JwkSet.parse("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + b64(unsigned(key.getModulus().toByteArray())) + "\",\"e\":\""
                + b64(unsigned(key.getPublicExponent().toByteArray())) + "\"}]}");
    }

    private static byte[] unsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            return stripped;
        }
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String claims(String iss, String aud, long exp, String nonce) {
        return "{\"iss\":\"" + iss + "\",\"sub\":\"alice\",\"aud\":" + aud + ",\"exp\":" + exp
                + ",\"iat\":" + (NOW.getEpochSecond() - 10)
                + (nonce == null ? "" : ",\"nonce\":\"" + nonce + "\"") + "}";
    }

    private static String valid() {
        return claims(ISSUER, "\"" + CLIENT + "\"", NOW.getEpochSecond() + 600, NONCE);
    }

    private static String sign(String header, String payload, KeyPair signer) throws Exception {
        String input = b64(header) + "." + b64(payload);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signature.sign());
    }

    private static String rs256(String payload) throws Exception {
        return sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", payload, pair);
    }

    private void rejected(String token) {
        assertThrows(InvalidTokenException.class, () -> validator.validate(token, NONCE));
    }

    @Test
    void aTokenWithoutSignatureIsRefused() {
        String payload = valid();
        rejected(b64("{\"alg\":\"none\"}") + "." + b64(payload) + ".");
    }

    @Test
    void aSymmetricAlgorithmIsRefusedWhatTheKey() throws Exception {
        // Key confusion: the public key must never be taken as an HMAC secret.
        rejected(sign("{\"alg\":\"HS256\",\"kid\":\"k1\"}", valid(), pair));
    }

    @Test
    void aTamperedPayloadIsRefused() throws Exception {
        String token = rs256(valid());
        String[] parts = token.split("\\.");
        String forged = claims(ISSUER, "\"" + CLIENT + "\"", NOW.getEpochSecond() + 600, NONCE)
                .replace("alice", "mallory");
        rejected(parts[0] + "." + b64(forged) + "." + parts[2]);
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefused() throws Exception {
        rejected(sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", valid(), otherPair));
    }

    @Test
    void anUnknownKeyIdIsRefused() throws Exception {
        rejected(sign("{\"alg\":\"RS256\",\"kid\":\"zz\"}", valid(), pair));
    }

    @Test
    void aWrongIssuerIsRefused() throws Exception {
        rejected(rs256(claims("https://evil.example", "\"" + CLIENT + "\"", NOW.getEpochSecond() + 600, NONCE)));
    }

    @Test
    void anAudienceWithoutThisClientIsRefused() throws Exception {
        rejected(rs256(claims(ISSUER, "[\"other\"]", NOW.getEpochSecond() + 600, NONCE)));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        rejected(rs256(claims(ISSUER, "\"" + CLIENT + "\"", NOW.getEpochSecond() - 31, NONCE)));
    }

    @Test
    void aReplayedNonceIsRefused() throws Exception {
        rejected(rs256(claims(ISSUER, "\"" + CLIENT + "\"", NOW.getEpochSecond() + 600, "another")));
    }

    @Test
    void aMissingNonceIsRefusedWhenOneWasSent() throws Exception {
        rejected(rs256(claims(ISSUER, "\"" + CLIENT + "\"", NOW.getEpochSecond() + 600, null)));
    }

    @Test
    void aMalformedTokenIsRefused() {
        rejected("not-a-token");
        rejected("a.b");
        rejected("a.b.c.d");
        rejected("");
    }

    @Test
    void aValidTokenIsAccepted() throws Exception {
        JsonObject claims = validator.validate(rs256(valid()), NONCE);
        assertEquals("alice", claims.getString("sub"));
    }

    @Test
    void severalAudiencesWithoutThisClientAsAuthorizedPartyAreRefused() throws Exception {
        rejected(rs256(claims(ISSUER, "[\"other\",\"" + CLIENT + "\"]", NOW.getEpochSecond() + 600, NONCE)));
    }

    @Test
    void anAudienceListThatHoldsThisClientAsAuthorizedPartyIsAccepted() throws Exception {
        String payload = claims(ISSUER, "[\"other\",\"" + CLIENT + "\"]", NOW.getEpochSecond() + 600, NONCE)
                .replace("\"sub\"", "\"azp\":\"" + CLIENT + "\",\"sub\"");
        assertEquals("alice", validator.validate(rs256(payload), NONCE).getString("sub"));
    }

    @Test
    void anExpiryWithinTheSkewIsAccepted() throws Exception {
        validator.validate(rs256(claims(ISSUER, "\"" + CLIENT + "\"", NOW.getEpochSecond() - 29, NONCE)), NONCE);
    }
}
