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
package io.vidocq.heisenberg.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Heisenberg jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, heisenberg#25):
 * Liberty's CDI runs the build compatible extension and the interceptor, Liberty's MicroProfile
 * Config supplies the §12 overrides. Liberty's mpFaultTolerance feature is off, so every policy
 * applied here is Heisenberg's.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("heisenberg.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void retryRetriesUntilSuccess() throws Exception {
        assertEquals("ok after 3", get("/ft/retry"));
    }

    @Test
    void configOverrideKeyedByTheBeanClassApplies() throws Exception {
        // RetryService/configured/Retry/maxRetries=1: 1 call + 1 retry, not 1 + 5.
        assertEquals("calls=2", get("/ft/configured"));
    }

    @Test
    void timeoutInterruptsASlowCall() throws Exception {
        assertEquals("TimeoutException", get("/ft/timeout"));
    }

    @Test
    void circuitBreakerOpensAfterTheThreshold() throws Exception {
        assertEquals("IllegalStateException,IllegalStateException,CircuitBreakerOpenException",
                get("/ft/circuit-breaker"));
    }

    @Test
    void fallbackMethodAnswersForAFailingCall() throws Exception {
        assertEquals("fallback for liberty", get("/ft/fallback"));
    }

    @Test
    void bulkheadRejectsACallBeyondItsCapacity() throws Exception {
        assertEquals("BulkheadException", get("/ft/bulkhead"));
    }

    @Test
    void metricsAreNamedAfterTheBeanClass() throws Exception {
        get("/ft/fallback");
        String classes = get("/ft/metric-classes");
        assertTrue(classes.contains(FallbackService.class.getName()), classes);
        assertTrue(!classes.contains("$"), "no container-generated subclass may name a metric: " + classes);
    }

    private static String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }
}
