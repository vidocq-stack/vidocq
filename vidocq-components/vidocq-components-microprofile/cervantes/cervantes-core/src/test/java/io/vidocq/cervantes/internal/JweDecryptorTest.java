package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tokens chiffrés MicroProfile JWT 2.1 (JWE, <em>sign-then-encrypt</em>) : RSA-OAEP-256 + A256GCM.
 */
class JweDecryptorTest {

    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://issuer.vidocq.dev";

    private static KeyPair SIGN; // signature de l'émetteur
    private static KeyPair ENC;  // chiffrement pour le destinataire

    @BeforeAll
    static void keys() throws Exception {
        SIGN = TestJwts.rsaKeyPair();
        ENC = TestJwts.rsaKeyPair();
    }

    private static String signedJws() throws Exception {
        JsonObject claims = Json.createObjectBuilder()
                .add("iss", ISS)
                .add("sub", "u-jwe")
                .add("exp", NOW.getEpochSecond() + 3600)
                .build();
        return TestJwts.sign(TestJwts.header(SignatureAlgorithm.RS256, null), claims, SIGN.getPrivate(),
                SignatureAlgorithm.RS256);
    }

    private static DefaultJwtValidator decryptingValidator() {
        return new DefaultJwtValidator(
                new ConfiguredKeyResolver(SIGN.getPublic()),
                JwtConfig.forIssuer(ISS),
                CLOCK,
                new JweDecryptor(ENC.getPrivate()));
    }

    @Test
    void encryptedToken_isDecryptedThenValidated() throws Exception {
        String jwe = TestJwe.encrypt(signedJws(), ENC.getPublic());
        JsonWebToken jwt = decryptingValidator().validate(jwe);
        assertEquals("u-jwe", jwt.getSubject());
    }

    @Test
    void plainSignedToken_stillValidatedByDecryptingValidator() throws Exception {
        JsonWebToken jwt = decryptingValidator().validate(signedJws()); // 3 parties → chemin JWS
        assertEquals("u-jwe", jwt.getSubject());
    }

    @Test
    void encryptedToken_withoutDecryptionKey_isRejected() throws Exception {
        String jwe = TestJwe.encrypt(signedJws(), ENC.getPublic());
        DefaultJwtValidator noDecrypt = new DefaultJwtValidator(
                new ConfiguredKeyResolver(SIGN.getPublic()), JwtConfig.forIssuer(ISS), CLOCK);
        assertThrows(JwtValidationException.class, () -> noDecrypt.validate(jwe));
    }

    @Test
    void tamperedCiphertext_isRejected() throws Exception {
        String jwe = TestJwe.encrypt(signedJws(), ENC.getPublic());
        String[] parts = jwe.split("\\.", -1);
        // altère un caractère du ciphertext → l'authentification GCM doit échouer
        char[] ct = parts[3].toCharArray();
        ct[0] = (ct[0] == 'A') ? 'B' : 'A';
        parts[3] = new String(ct);
        String tampered = String.join(".", parts);
        assertThrows(JwtValidationException.class, () -> decryptingValidator().validate(tampered));
    }

    @Test
    void wrongDecryptionKey_isRejected() throws Exception {
        String jwe = TestJwe.encrypt(signedJws(), ENC.getPublic());
        KeyPair otherEnc = TestJwts.rsaKeyPair();
        DefaultJwtValidator wrongKey = new DefaultJwtValidator(
                new ConfiguredKeyResolver(SIGN.getPublic()), JwtConfig.forIssuer(ISS), CLOCK,
                new JweDecryptor(otherEnc.getPrivate()));
        assertThrows(JwtValidationException.class, () -> wrongKey.validate(jwe));
    }
}
