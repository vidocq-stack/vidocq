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
package io.vidocq.runtime.examples.servlet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bistro's JSP pages, compiled by Ibarra at build time and served by Foy, with expressions evaluated by Peano.
 * The application runs as it is launched ({@link BistroApplication}), its module in the application layer, with
 * {@code -Dpeano.reflection=deny}: an expression on a {@code @ELAccessible} record must go through the accessor
 * Peano generated, never through reflection.
 */
class BistroPagesTest {

    private static BistroApplication application;
    private static String base;

    @BeforeAll
    static void launch() throws Exception {
        application = BistroApplication.start("bistro-pages", "-Dpeano.reflection=deny");
        base = application.base();
    }

    @AfterAll
    static void stop() throws Exception {
        if (application != null) {
            application.stop();
        }
    }

    @Test
    void theAboutPageIsAJspWithATagLibraryFunction() throws IOException {
        Response about = new Browser().get("/about.jsp?from=IntelliJ");

        assertEquals(200, about.status(), about.body() + "\n" + application.log());
        assertTrue(about.body().contains("Welcome, visitor from IntelliJ"), about.body());
        assertTrue(about.body().contains("€18.50"), "bistro:euros formats the price: " + about.body());
    }

    @Test
    void theReceiptIsRenderedByAJspFromARequestAttribute() throws IOException {
        Browser browser = new Browser();
        browser.post("/basket", "add=soup");
        browser.post("/basket", "add=soup");
        browser.post("/basket", "add=tarte");

        Response receipt = browser.get("/receipt");

        assertEquals(200, receipt.status(), receipt.body() + "\n" + application.log());
        assertTrue(receipt.body().contains("2 × French onion soup"), receipt.body());
        assertTrue(receipt.body().contains("1 × Tarte Tatin"), receipt.body());
        assertTrue(receipt.body().contains("Total €23.00"), receipt.body());
    }

    @Test
    void theReceiptViewIsOutOfTheClientsReach() throws IOException {
        assertEquals(404, new Browser().get("/WEB-INF/views/receipt.jsp").status());
    }

    private record Response(int status, String body) {}

    /** An HTTP client that keeps the session cookie and never follows a redirect on its own. */
    private static final class Browser {

        private String sessionCookie;

        Response get(String path) throws IOException {
            return send("GET", path, null);
        }

        Response post(String path, String form) throws IOException {
            return send("POST", path, form);
        }

        private Response send(String method, String path, String form) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            if (sessionCookie != null) {
                connection.setRequestProperty("Cookie", sessionCookie);
            }
            if (form != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
            if (cookies != null) {
                for (String cookie : cookies) {
                    String pair = cookie.split(";", 2)[0];
                    if (pair.startsWith("JSESSIONID=")) {
                        sessionCookie = pair;
                    }
                }
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, body);
        }
    }
}
