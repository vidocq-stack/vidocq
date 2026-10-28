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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ParsedJwt} is a value: its byte arrays take part in equality by content, and it never prints the token. */
class ParsedJwtTest {

    private static final String RAW = "aGVhZGVy.cGF5bG9hZA.c2ln";
    private static final JsonObject HEADER = Json.createObjectBuilder().add("alg", "RS256").build();
    private static final JsonObject CLAIMS = Json.createObjectBuilder().add("sub", "alice").build();

    private static ParsedJwt parsed(String signature) {
        return new ParsedJwt(HEADER, CLAIMS, "aGVhZGVy.cGF5bG9hZA".getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII), RAW);
    }

    @Test
    void equalContent_isEqualWithSameHashCode() {
        ParsedJwt a = parsed("sig");
        ParsedJwt b = parsed("sig");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void anyDifferentComponent_breaksEquality() {
        ParsedJwt base = parsed("sig");
        byte[] input = base.signingInput();
        byte[] signature = base.signature();
        assertNotEquals(base, parsed("other"));
        assertNotEquals(base, new ParsedJwt(CLAIMS, CLAIMS, input, signature, RAW));
        assertNotEquals(base, new ParsedJwt(HEADER, HEADER, input, signature, RAW));
        assertNotEquals(base, new ParsedJwt(HEADER, CLAIMS, signature, signature, RAW));
        assertNotEquals(base, new ParsedJwt(HEADER, CLAIMS, input, signature, "other.raw.token"));
    }

    @Test
    void toString_showsArrayLengthsAndHidesTheRawToken() {
        String text = parsed("sig").toString();
        assertTrue(text.contains("signature=3 bytes"), text);
        assertFalse(text.contains(RAW), text);
        assertFalse(text.contains("[B@"), text);
    }
}
