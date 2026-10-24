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
package io.vidocq.bodoni.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bodoni-core jar, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, PLAN.md milestone
 * B7): although the server ships its own JSTL, the tag handlers that run are Bodoni's. A probe tag nested
 * in the action under test reports the class of its parent handler.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("bodoni.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void coreTagsAreBodoni() throws Exception {
        String body = get("/core.jsp");
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.core.ForEachTag]"), body);
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.core.IfTag]"), body);
        assertTrue(body.contains("ITEMS=a,b,c"), body);
    }

    @Test
    void legacyCoreUriIsBodoni() throws Exception {
        String body = get("/core-legacy.jsp");
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.core.ForEachTag]"), body);
    }

    @Test
    void fmtTagsAreBodoni() throws Exception {
        String body = get("/fmt.jsp");
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.fmt.BundleTag]"), body);
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.fmt.TimeZoneTag]"), body);
    }

    @Test
    void fmtMessageReadsTheApplicationBundle() throws Exception {
        String body = get("/fmt.jsp");
        assertTrue(body.contains("MSG=Hello from the application bundle"), body);
    }

    @Test
    void fnFunctionsAreBodoni() throws Exception {
        assertTrue(get("/fn.jsp").contains("LEN=3"));
        String source = generatedSource("fn");
        assertTrue(source.contains("io.vidocq.bodoni.core.functions.Functions"), source);
        assertFalse(source.contains("org.apache.taglibs"), source);
    }

    @Test
    void xmlTagsAreBodoni() throws Exception {
        String body = get("/xml.jsp");
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.xml.ForEachTag]"), body);
        assertTrue(body.contains("NAMES=ada,grace"), body);
    }

    @Test
    void sqlQueryRunsOnTheApplicationDriver() throws Exception {
        String body = get("/sql.jsp");
        assertTrue(body.contains("[parent=io.vidocq.bodoni.core.tags.sql.TransactionTag]"), body);
        assertTrue(body.contains("ROWS=2"), body);
    }

    /** The servlet source Liberty keeps for a page (server.xml: keepGenerated), found under the workarea. */
    private static String generatedSource(String jspName) throws IOException {
        Path workarea = Path.of(System.getProperty("bodoni.it.workarea"));
        List<Path> found;
        try (Stream<Path> files = Files.walk(workarea)) {
            found = files.filter(f -> f.getFileName().toString().equals("_" + jspName + ".java")).toList();
        }
        assertEquals(1, found.size(),
                "expected exactly one _" + jspName + ".java under " + workarea + ", found " + found);
        return Files.readString(found.get(0));
    }

    private static String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = getRaw(path);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static HttpResponse<String> getRaw(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
