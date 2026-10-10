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
package io.vidocq.dirac.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dirac jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, dirac#23): Liberty's
 * CDI runs Dirac's extension and interceptors, and Liberty's Jakarta REST serves Dirac's
 * {@code /metrics} in the OpenMetrics text format (MP Metrics 5.1 §4). Liberty's mpMetrics feature
 * is off, so every metric here is Dirac's.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("dirac.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void metricsEndpointReportsTheApplicationMetrics() throws Exception {
        send(HttpRequest.newBuilder(URI.create(BASE + "/orders")).POST(HttpRequest.BodyPublishers.noBody()));
        send(HttpRequest.newBuilder(URI.create(BASE + "/orders")).POST(HttpRequest.BodyPublishers.noBody()));

        String metrics = send(HttpRequest.newBuilder(URI.create(BASE + "/metrics/application")).GET());
        assertTrue(metrics.matches("(?ms).*^orders_placed_total(\\{[^}]*\\})? 2(\\.0)?$.*"), metrics);
        assertTrue(metrics.matches("(?ms).*^orders_priced_seconds_count(\\{[^}]*\\})? 2(\\.0)?$.*"), metrics);
        assertTrue(metrics.matches("(?ms).*^orders_queue(\\{[^}]*\\})? 7(\\.0)?$.*"), metrics);
    }

    @Test
    void baseMetricsArePublished() throws Exception {
        String metrics = send(HttpRequest.newBuilder(URI.create(BASE + "/metrics/base")).GET());
        assertTrue(metrics.contains("# TYPE classloader_loadedClasses gauge"), metrics);
    }

    private static String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
