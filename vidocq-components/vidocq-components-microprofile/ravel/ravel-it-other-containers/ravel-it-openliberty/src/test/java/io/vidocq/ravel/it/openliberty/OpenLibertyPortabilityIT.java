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
package io.vidocq.ravel.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ravel jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, ravel#25): Liberty's
 * CDI runs Ravel's build compatible extension, and {@code ConfigProvider} answers with Ravel.
 * Liberty's mpConfig feature is off, so every value here comes from Ravel.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("ravel.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void configPropertyIsInjected() throws Exception {
        assertEquals("hello from ravel|fallback", get("/config/greeting"));
    }

    @Test
    void configPropertiesAreInjected() throws Exception {
        assertEquals("localhost:8080", get("/config/server"));
    }

    @Test
    void configProviderAndConfigBeanAreRavel() throws Exception {
        String[] answer = get("/config/implementation").split("\\|");
        assertTrue(answer[0].startsWith("io.vidocq.ravel."), answer[0]);
        assertEquals("hello from ravel", answer[1]);
    }

    private static String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }
}
