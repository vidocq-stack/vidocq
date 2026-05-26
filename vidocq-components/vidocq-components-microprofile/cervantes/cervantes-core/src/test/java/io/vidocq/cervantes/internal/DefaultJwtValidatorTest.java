package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du moteur de validation (MicroProfile JWT 2.1 §2 ; RFC 7515/7519).
 * Horloge figée pour rendre {@code exp}/{@code nbf} déterministes.
 */
class DefaultJwtValidatorTest {

    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://issuer.vidocq.dev";
    private static final String AUD = "cervantes-tests";

    private static KeyPair RSA;
    private static KeyPair EC;

    @BeforeAll
    static void generateKeys() throws Exception {
        RSA = TestJwts.rsaKeyPair();
        EC = TestJwts.ecKeyPair("secp256r1"); // P-256 → ES256
    }

    // --- helpers -----------------------------------------------------------

    private static JsonObjectBuilder baseClaims() {
        return Json.createObjectBuilder()
                .add("iss", ISS)
                .add("aud", AUD)
                .add("sub", "1234567890")
                .add("upn", "alice@vidocq.dev")
                .add("jti", "token-id-1")
                .add("iat", NOW.getEpochSecond() - 60)
                .add("exp", NOW.getEpochSecond() + 3600)
                .add("groups", Json.createArrayBuilder().add("admin").add("user"));
    }

    private static DefaultJwtValidator rsaValidator() {
        return new DefaultJwtValidator(
                new ConfiguredKeyResolver(RSA.getPublic()),
                JwtConfig.of(ISS, Set.of(AUD)),
                CLOCK);
    }

    private static String rsaToken(JsonObject claims) throws Exception {
        return TestJwts.sign(TestJwts.header(SignatureAlgorithm.RS256, null), claims, RSA.getPrivate(),
                SignatureAlgorithm.RS256);
    }

    // --- tokens valides ----------------------------------------------------

    @Test
    void validRsaToken_isAcceptedAndExposesClaims() throws Exception {
        JsonWebToken jwt = rsaValidator().validate(rsaToken(baseClaims().build()));

        assertEquals("1234567890", jwt.getSubject());
        assertEquals("alice@vidocq.dev", jwt.getName()); // upn prioritaire
        assertEquals(ISS, jwt.getIssuer());
        assertEquals(Set.of("admin", "user"), jwt.getGroups());
        assertEquals(NOW.getEpochSecond() + 3600, jwt.getExpirationTime());
        assertTrue(jwt.getAudience().contains(AUD));
        assertNotNull(jwt.getRawToken());
    }

    @Test
    void validEcToken_isAccepted() throws Exception {
        // ES256 : exerce le transcodage JOSE R‖S ⇄ DER
        String token = TestJwts.sign(
                TestJwts.header(SignatureAlgorithm.ES256, "k1"),
                baseClaims().build(),
                EC.getPrivate(),
                SignatureAlgorithm.ES256);

        DefaultJwtValidator validator = new DefaultJwtValidator(
                new ConfiguredKeyResolver(EC.getPublic()),
                JwtConfig.of(ISS, Set.of(AUD)),
                CLOCK);

        JsonWebToken jwt = validator.validate(token);
        assertEquals("1234567890", jwt.getSubject());
    }

    @Test
    void clockSkew_toleratesRecentlyExpiredToken() throws Exception {
        // exp 30 s dans le passé, skew par défaut 60 s → encore accepté
        String token = rsaToken(baseClaims().add("exp", NOW.getEpochSecond() - 30).build());
        assertNotNull(rsaValidator().validate(token));
    }

    // --- rejets ------------------------------------------------------------

    @Test
    void expiredToken_isRejected() throws Exception {
        String token = rsaToken(baseClaims().add("exp", NOW.getEpochSecond() - 3600).build());
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(token));
    }

    @Test
    void notYetValidToken_isRejected() throws Exception {
        String token = rsaToken(baseClaims().add("nbf", NOW.getEpochSecond() + 3600).build());
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(token));
    }

    @Test
    void tamperedSignature_isRejected() throws Exception {
        // signé avec une AUTRE clé RSA → la signature ne vérifie pas avec la clé attendue
        KeyPair other = TestJwts.rsaKeyPair();
        String token = TestJwts.sign(TestJwts.header(SignatureAlgorithm.RS256, null),
                baseClaims().build(), other.getPrivate(), SignatureAlgorithm.RS256);
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(token));
    }

    @Test
    void wrongIssuer_isRejected() throws Exception {
        String token = rsaToken(baseClaims().add("iss", "https://evil.example").build());
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(token));
    }

    @Test
    void wrongAudience_isRejected() throws Exception {
        String token = rsaToken(baseClaims().add("aud", "some-other-service").build());
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(token));
    }

    @Test
    void missingExpiration_isRejectedWhenRequired() throws Exception {
        JsonObjectBuilder b = baseClaims();
        JsonObject withoutExp = Json.createObjectBuilder(b.build()).remove("exp").build();
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(rsaToken(withoutExp)));
    }

    @Test
    void malformedToken_isRejected() {
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate("not-a-jwt"));
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate("only.two"));
    }

    @Test
    void unsupportedAlgorithm_isRejected() throws Exception {
        // alg "none" (token non signé) doit être refusé
        String h = TestJwts.B64URL.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String p = TestJwts.B64URL.encodeToString("{\"sub\":\"x\"}".getBytes());
        String token = h + "." + p + "."; // signature vide
        assertThrows(JwtValidationException.class, () -> rsaValidator().validate(token));
    }

    @Test
    void issuerNotChecked_whenNotConfigured() throws Exception {
        // config sans issuer : iss du token ignoré
        DefaultJwtValidator validator = new DefaultJwtValidator(
                new ConfiguredKeyResolver(RSA.getPublic()),
                new JwtConfig(Optional.empty(), Set.of(), Duration.ofSeconds(60), true),
                CLOCK);
        String token = rsaToken(baseClaims().add("iss", "https://whatever").build());
        assertNotNull(validator.validate(token));
    }
}
