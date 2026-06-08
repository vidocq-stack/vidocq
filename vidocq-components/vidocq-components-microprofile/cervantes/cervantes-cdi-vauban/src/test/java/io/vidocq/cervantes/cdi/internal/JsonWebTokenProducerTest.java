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
package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.internal.DefaultJsonWebToken;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Producer for the {@code JsonWebToken} principal from the request context. */
class JsonWebTokenProducerTest {

    private final JsonWebTokenProducer producer = new JsonWebTokenProducer();

    @Test
    void producesTheTokenSetOnTheContext() {
        JsonObject claims = Json.createObjectBuilder().add("sub", "u1").add("upn", "alice").build();
        JsonWebToken token = new DefaultJsonWebToken(claims, "raw-token");

        JsonWebTokenContext context = new JsonWebTokenContext();
        context.setToken(token);

        assertSame(token, producer.currentToken(context));
    }

    @Test
    void producesAnonymousPrincipalWhenNoToken() {
        JsonWebToken jwt = producer.currentToken(new JsonWebTokenContext());

        assertNull(jwt.getName(), "anonymous principal has no name");
        // MP JWT TCK EmptyTokenTest: an unauthenticated/empty token exposes null claim names
        //(not an empty set) — the DefaultJsonWebToken.anonymous() contract.
        assertNull(jwt.getClaimNames(), "anonymous principal exposes no claim names");
    }

    @Test
    void contextClearRemovesToken() {
        JsonWebTokenContext context = new JsonWebTokenContext();
        context.setToken(new DefaultJsonWebToken(Json.createObjectBuilder().add("sub", "u1").build(), "raw"));
        context.clear();

        assertNull(producer.currentToken(context).getName(), "cleared context yields an anonymous principal");
    }
}
