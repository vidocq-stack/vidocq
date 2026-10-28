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

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim validation rules applied after the signature check: RFC 7519 §4.1 ({@code exp}, {@code nbf},
 * {@code iat}, {@code iss}, {@code aud}) and MicroProfile JWT 2.2 §4.1 (principal claims) and
 * §9.2.1 ({@code mp.jwt.verify.token.age}).
 */
class JwtClaimsValidatorTest {

    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");
    private static final long NOW_S = NOW.getEpochSecond();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://issuer.vidocq.dev";
    private static final String AUD = "cervantes-tests";
    private static final String EXP = "exp";
    private static final String IAT = "iat";
    private static final String AUD_CLAIM = "aud";

    private final JwtClaimsValidator validator = new JwtClaimsValidator();

    private static JwtConfig config(Optional<String> issuer, Set<String> audiences, boolean requireExp,
                                    Optional<Long> tokenAge) {
        return new JwtConfig(issuer, audiences, Duration.ofSeconds(60), requireExp, tokenAge, false);
    }

    private static JwtConfig lenient() {
        return config(Optional.empty(), Set.of(), false, Optional.empty());
    }

    private static JsonObjectBuilder claims() {
        return Json.createObjectBuilder().add("sub", "alice").add(EXP, NOW_S + 3600);
    }

    private String rejection(JsonObject claims, JwtConfig config) {
        return assertThrows(JwtValidationException.class, () -> validator.validate(claims, config, CLOCK))
                .getMessage();
    }

    @Test
    void validClaims_areAccepted() {
        JsonObject claims = claims().add("iss", ISS).add(AUD_CLAIM, AUD).add(IAT, NOW_S - 10).build();
        JwtConfig config = config(Optional.of(ISS), Set.of(AUD), true, Optional.of(60L));
        assertDoesNotThrow(() -> validator.validate(claims, config, CLOCK));
    }

    @Test
    void missingExp_isRejectedOnlyWhenRequired() {
        JsonObject claims = Json.createObjectBuilder().add("sub", "alice").build();
        assertTrue(rejection(claims, config(Optional.empty(), Set.of(), true, Optional.empty())).contains(EXP));
        assertDoesNotThrow(() -> validator.validate(claims, lenient(), CLOCK));
    }

    @Test
    void nullExp_isTreatedAsAbsent() {
        JsonObject claims = Json.createObjectBuilder().add("sub", "alice").addNull(EXP).build();
        assertDoesNotThrow(() -> validator.validate(claims, lenient(), CLOCK));
    }

    @Test
    void nonNumericExp_isRejected() {
        JsonObject claims = Json.createObjectBuilder().add("sub", "alice").add(EXP, "tomorrow").build();
        assertTrue(rejection(claims, lenient()).contains("must be a number"));
    }

    @Test
    void expiredBeyondSkew_isRejected_withinSkew_isAccepted() {
        JsonObject expired = claims().add(EXP, NOW_S - 61).build();
        assertTrue(rejection(expired, lenient()).contains("expired"));
        JsonObject withinSkew = claims().add(EXP, NOW_S - 59).build();
        assertDoesNotThrow(() -> validator.validate(withinSkew, lenient(), CLOCK));
    }

    @Test
    void iatAfterExp_isRejected() {
        JsonObject claims = claims().add(IAT, NOW_S + 7200).build();
        assertTrue(rejection(claims, lenient()).contains("iat > exp"));
    }

    @Test
    void iatWithoutExp_isAccepted() {
        JsonObject claims = Json.createObjectBuilder().add("sub", "alice").add(IAT, NOW_S).build();
        assertDoesNotThrow(() -> validator.validate(claims, lenient(), CLOCK));
    }

    @Test
    void tokenOlderThanTokenAge_isRejected() {
        JsonObject claims = claims().add(IAT, NOW_S - 120).build();
        JwtConfig config = config(Optional.empty(), Set.of(), false, Optional.of(60L));
        assertTrue(rejection(claims, config).contains("token.age=60s"));
    }

    @Test
    void tokenAge_isIgnoredWithoutIat() {
        JsonObject claims = claims().build();
        JwtConfig config = config(Optional.empty(), Set.of(), false, Optional.of(60L));
        assertDoesNotThrow(() -> validator.validate(claims, config, CLOCK));
    }

    @Test
    void nbfInTheFutureBeyondSkew_isRejected() {
        JsonObject claims = claims().add("nbf", NOW_S + 61).build();
        assertTrue(rejection(claims, lenient()).contains("nbf"));
        JsonObject withinSkew = claims().add("nbf", NOW_S + 59).build();
        assertDoesNotThrow(() -> validator.validate(withinSkew, lenient(), CLOCK));
    }

    @Test
    void issuerMismatchOrMissing_isRejected() {
        JwtConfig config = config(Optional.of(ISS), Set.of(), false, Optional.empty());
        assertTrue(rejection(claims().add("iss", "https://evil").build(), config).contains("issuer mismatch"));
        assertTrue(rejection(claims().build(), config).contains("issuer mismatch"));
    }

    @Test
    void audienceArray_matchesWhenAnyEntryIsConfigured() {
        JsonObject claims = claims().add(AUD_CLAIM, Json.createArrayBuilder().add("other").add(1).add(AUD)).build();
        JwtConfig config = config(Optional.empty(), Set.of(AUD), false, Optional.empty());
        assertDoesNotThrow(() -> validator.validate(claims, config, CLOCK));
    }

    @Test
    void audienceMismatchOrMissing_isRejected() {
        JwtConfig config = config(Optional.empty(), Set.of(AUD), false, Optional.empty());
        assertTrue(rejection(claims().add(AUD_CLAIM, "other").build(), config).contains("audience mismatch"));
        assertTrue(rejection(claims().build(), config).contains("audience mismatch"));
        assertTrue(rejection(claims().add(AUD_CLAIM, 42).build(), config).contains("audience mismatch"));
    }

    @Test
    void principalClaim_isRequired() {
        JsonObject none = Json.createObjectBuilder().add(EXP, NOW_S + 60).build();
        assertTrue(rejection(none, lenient()).contains("upn"));
        JsonObject upnOnly = Json.createObjectBuilder().add("upn", "alice@vidocq.dev").build();
        assertDoesNotThrow(() -> validator.validate(upnOnly, lenient(), CLOCK));
        JsonObject preferredOnly = Json.createObjectBuilder().add("preferred_username", "alice").build();
        assertDoesNotThrow(() -> validator.validate(preferredOnly, lenient(), CLOCK));
    }
}
