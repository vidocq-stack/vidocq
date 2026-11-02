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
import jakarta.json.JsonException;
import jakarta.json.spi.JsonProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link JwtParser} looks the JSON-P provider up once, not once per token segment. */
class JwtParserTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static String token() {
        String header = B64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString("{\"sub\":\"alice\"}".getBytes(StandardCharsets.UTF_8));
        return header + '.' + payload + '.' + B64.encodeToString("sig".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsingManyTokens_looksTheProviderUpOnce() throws Exception {
        var lookups = new AtomicInteger();
        var parser = new JwtParser(() -> {
            lookups.incrementAndGet();
            return JsonProvider.provider();
        });

        for (int i = 0; i < 5; i++) {
            ParsedJwt jwt = parser.parse(token());
            assertEquals("alice", jwt.claims().getString("sub"));
            assertEquals("RS256", jwt.header().getString("alg"));
        }

        assertEquals(1, lookups.get());
    }

    @Test
    void aProviderThatCannotBeFound_isStillReportedAsInvalidJson() {
        var parser = new JwtParser(() -> {
            throw new JsonException("no provider");
        });

        var thrown = assertThrows(JwtValidationException.class, () -> parser.parse(token()));

        assertEquals("invalid JSON in JWT header", thrown.getMessage());
    }

    @Test
    void theDefaultConstructor_resolvesTheProviderOnDemand() throws Exception {
        assertEquals("alice", new JwtParser().parse(token()).claims().getString("sub"));
    }
}
