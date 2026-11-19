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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Cervantes jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, cervantes#24):
 * Liberty's CDI runs the build compatible extension, Liberty's Jakarta REST registers the
 * authentication filter and the {@code @RolesAllowed} feature, and Liberty's MicroProfile Config
 * supplies the verification key. Liberty's mpJwt and appSecurity features are off, so every
 * decision here is Cervantes'.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("cervantes.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void validTokenReachesARolesAllowedResource() throws Exception {
        HttpResponse<String> response = get("/secured/admin", Tokens.sign(Tokens.signingKey(), "alice", "admin", "user"));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("admin alice [admin, user]", response.body());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        assertEquals(401, get("/secured/admin", null).statusCode());
    }

    @Test
    void tokenSignedByAnotherKeyIsUnauthorized() throws Exception {
        String forged = Tokens.sign(Tokens.newKeyPair().getPrivate(), "mallory", "admin");
        assertEquals(401, get("/secured/admin", forged).statusCode());
    }

    @Test
    void missingRoleIsForbidden() throws Exception {
        assertEquals(403, get("/secured/auditor", Tokens.sign(Tokens.signingKey(), "alice", "admin")).statusCode());
    }

    @Test
    void permitAllNeedsNoToken() throws Exception {
        HttpResponse<String> response = get("/secured/public", null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("public", response.body());
    }

    private static HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
