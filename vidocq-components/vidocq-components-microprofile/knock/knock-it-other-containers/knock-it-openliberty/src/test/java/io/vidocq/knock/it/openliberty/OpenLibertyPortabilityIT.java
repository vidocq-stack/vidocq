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
package io.vidocq.knock.it.openliberty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Knock jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15): Liberty's CDI
 * discovers the checks and Knock's beans, Liberty's Jakarta REST mounts {@code KnockHealthResource},
 * and the MicroProfile Health 4.0 §3 endpoints answer over HTTP. Liberty's mpHealth feature is off,
 * so every answer comes from Knock.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("knock.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void liveIsUpWithTheCheckData() throws Exception {
        HttpResponse<String> response = get("/health/live");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"liberty-live\""), response.body());
        assertTrue(response.body().contains("\"openliberty\""), response.body());
    }

    @Test
    void readyIsDown() throws Exception {
        HttpResponse<String> response = get("/health/ready");
        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"liberty-not-ready\""), response.body());
    }

    @Test
    void startedIsUp() throws Exception {
        HttpResponse<String> response = get("/health/started");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"liberty-started\""), response.body());
    }

    @Test
    void aggregateHoldsEveryCheckAndIsDown() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(503, response.statusCode(), response.body());
        for (String name : new String[]{"liberty-live", "liberty-not-ready", "liberty-started"}) {
            assertTrue(response.body().contains('"' + name + '"'), () -> name + " missing: " + response.body());
        }
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
