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
package io.vidocq.it.grimm.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Grimm jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, grimm#22): Liberty's
 * CDI runs Grimm's extension, Liberty's Jakarta REST serves {@code /openapi}, and the document lists
 * every resource of the application — a CDI bean, and a resource that is not one, described by
 * grimm-processor. Liberty's mpOpenAPI feature is off, so the document here is Grimm's.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("grimm.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String document() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(BASE + "/openapi?format=json")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    @Test
    void documentListsTheBeanResourceAndItsSchema() throws Exception {
        String document = document();
        assertTrue(document.contains("\"/orders\""), document);
        assertTrue(document.contains("List the orders"), document);
        assertTrue(document.contains("\"Order\""), document);
    }

    @Test
    void documentListsTheResourceThatIsNotABean() throws Exception {
        String document = document();
        assertTrue(document.contains("\"/greetings\""), document);
        assertTrue(document.contains("Say hello"), document);
    }

    @Test
    void documentDoesNotListGrimmItself() throws Exception {
        assertFalse(document().contains("\"/openapi\""));
    }
}
