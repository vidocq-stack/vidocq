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
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vidocq#232: an application made of JSP pages only, run by {@code vidocq:run} with its module in the
 * application layer. Foy finds Ibarra's engine and the compiled pages there, and deploys the application
 * although it has no servlet, filter or listener of its own.
 */
class AppLayerJspIT {

    @Test
    void aJspOnlyApplicationServesItsPagesFromTheApplicationLayer(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Files.writeString(project.resolve("jsp/src/main/resources/vidocq.properties"),
                "vidocq.chappe.listener.default.host=127.0.0.1\nvidocq.chappe.listener.default.port=" + port + "\n");
        Fixture.install(project, Fixture.logs().resolve("jsp-install.log"));
        Path log = Fixture.logs().resolve("jsp-run.log");

        Process mvn = Fixture.start(project.resolve("jsp"), log, "vidocq:run");
        try {
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertTrue(Fixture.read(log).contains("JSP pages compiled by Ibarra: 1"), Fixture.read(log));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello.jsp?x=Ada")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body() + "\n" + Fixture.read(log));
            assertEquals("Hello Ada!", response.body().strip());
        } finally {
            Fixture.stop(mvn);
        }
    }
}
