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

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.Claims;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim conversions of {@link DefaultJsonWebToken} (MicroProfile JWT 2.2 §5.1 {@code JsonWebToken}):
 * {@code Long} for the time claims, {@code Set<String>} for {@code aud}/{@code groups}, Java values
 * for the other scalars, and the {@code null} contract of the anonymous token.
 */
class DefaultJsonWebTokenTest {

    private static final String RAW = "raw.token.value";
    private static final String UPN = "upn";
    private static final String PREFERRED = "preferred_username";
    private static final String GROUPS = "groups";

    private static DefaultJsonWebToken token(JsonObject payload) {
        return new DefaultJsonWebToken(payload, RAW);
    }

    @Test
    void name_prefersUpnThenPreferredUsernameThenSub() {
        JsonObject all = Json.createObjectBuilder().add(UPN, "u").add(PREFERRED, "p").add("sub", "s").build();
        assertEquals("u", token(all).getName());
        assertEquals("p", token(Json.createObjectBuilder(all).remove(UPN).build()).getName());
        assertEquals("s", token(Json.createObjectBuilder().add("sub", "s").build()).getName());
        assertNull(token(JsonValue.EMPTY_JSON_OBJECT).getName());
    }

    @Test
    void anonymousToken_returnsNullEverywhere() {
        DefaultJsonWebToken anonymous = DefaultJsonWebToken.anonymous();
        assertNull(anonymous.getName());
        assertNull(anonymous.getClaimNames());
        assertNull(anonymous.getRawToken());
        assertNull(anonymous.getClaim(Claims.raw_token.name()));
        assertSame(JsonValue.NULL, anonymous.rawClaim(UPN));
    }

    @Test
    void emptyPayloadWithRawToken_isNotAnonymous() {
        DefaultJsonWebToken empty = token(JsonValue.EMPTY_JSON_OBJECT);
        assertEquals(Set.of(), empty.getClaimNames());
        assertEquals(RAW, empty.getClaim(Claims.raw_token.name()));
    }

    @Test
    void timeClaims_areLongs_andNonNumericTimeClaimsAreNull() {
        JsonObject payload = Json.createObjectBuilder()
                .add("exp", 10).add("iat", 20).add("nbf", 30).add("auth_time", 40).add("updated_at", "never").build();
        DefaultJsonWebToken jwt = token(payload);
        assertEquals(10L, (Long) jwt.getClaim("exp"));
        assertEquals(20L, (Long) jwt.getClaim("iat"));
        assertEquals(30L, (Long) jwt.getClaim("nbf"));
        assertEquals(40L, (Long) jwt.getClaim("auth_time"));
        assertNull(jwt.getClaim("updated_at"));
    }

    @Test
    void groupsAndAudience_areStringSets() {
        JsonObject payload = Json.createObjectBuilder()
                .add(GROUPS, Json.createArrayBuilder().add("a").add(1).add("b"))
                .add("aud", "single").build();
        DefaultJsonWebToken jwt = token(payload);
        assertEquals(Set.of("a", "b"), jwt.getGroups());
        assertEquals(Set.of("single"), jwt.getAudience());
        assertEquals(Set.of(), token(Json.createObjectBuilder().add(GROUPS, 5).build()).getGroups());
    }

    @Test
    void otherClaims_areConvertedToJavaValues() {
        JsonObject address = Json.createObjectBuilder().add("city", "Paris").build();
        JsonObject payload = Json.createObjectBuilder()
                .add("s", "text").add("i", 42).add("d", 1.5).add("t", true).add("f", false)
                .add("o", address).addNull("n").build();
        DefaultJsonWebToken jwt = token(payload);
        assertEquals("text", jwt.getClaim("s"));
        assertEquals(42L, (Long) jwt.getClaim("i"));
        assertEquals(1.5, (Double) jwt.getClaim("d"));
        assertEquals(Boolean.TRUE, jwt.getClaim("t"));
        assertEquals(Boolean.FALSE, jwt.getClaim("f"));
        assertEquals(address, jwt.getClaim("o"));
        assertNull(jwt.getClaim("n"));
        assertNull(jwt.getClaim("absent"));
    }

    @Test
    void rawClaim_exposesTheJsonValue() {
        JsonObject payload = Json.createObjectBuilder().add(UPN, "u").build();
        DefaultJsonWebToken jwt = token(payload);
        assertInstanceOf(JsonString.class, jwt.rawClaim(UPN));
        assertSame(JsonValue.NULL, jwt.rawClaim("absent"));
        assertTrue(jwt.getClaimNames().contains(UPN));
    }
}
