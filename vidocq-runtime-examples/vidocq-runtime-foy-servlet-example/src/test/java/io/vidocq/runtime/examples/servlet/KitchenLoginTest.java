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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kitchen's FORM login, end to end: a client that keeps its session cookie, as a browser does, logs in, reaches
 * the kitchen, logs out, and is asked to log in again. With BASIC authentication the browser kept the credentials
 * until it closed, and no page could sign the user out.
 *
 * <p>The application runs as a jlink image launches it ({@link BistroApplication}).
 */
class KitchenLoginTest {

    private static BistroApplication application;
    private static String base;

    @BeforeAll
    static void launch() throws Exception {
        application = BistroApplication.start("bistro");
        base = application.base();
    }

    @AfterAll
    static void stop() throws Exception {
        if (application != null) {
            application.stop();
        }
    }

    @Test
    void theKitchenSendsAnAnonymousVisitorToTheLoginForm() throws IOException {
        Browser browser = new Browser();

        Response kitchen = browser.get("/kitchen");

        assertEquals(200, kitchen.status(), "Foy forwards to the login page");
        assertTrue(kitchen.body().contains("action=\"/j_security_check\""), kitchen.body());
        assertTrue(kitchen.body().contains("name=\"j_username\""), kitchen.body());
        assertTrue(kitchen.body().contains("name=\"j_password\""), kitchen.body());
    }

    @Test
    void aWrongPasswordShowsTheErrorAndGrantsNothing() throws IOException {
        Browser browser = new Browser();
        browser.get("/kitchen");

        Response login = browser.login("chef", "wrong");

        assertTrue(login.body().contains("Wrong user name or password"), login.body());
        assertTrue(browser.get("/kitchen").body().contains("name=\"j_password\""), "still asked to log in");
    }

    @Test
    void aChefLogsInReachesTheKitchenAndLogsOut() throws IOException {
        Browser browser = new Browser();
        browser.get("/kitchen");

        Response login = browser.login("chef", "chef");
        assertEquals(302, login.status(), "back to the page that asked for the login");
        assertEquals("/kitchen", URI.create(login.location()).getPath());

        Response kitchen = browser.get("/kitchen");
        assertEquals(200, kitchen.status());
        assertTrue(kitchen.body().contains("<h1>Kitchen</h1>"), kitchen.body());
        assertTrue(kitchen.body().contains("Signed in as <b>chef</b>"), kitchen.body());
        assertTrue(kitchen.body().contains("action=\"/logout\""), "every page offers to log out: " + kitchen.body());
        assertTrue(browser.get("/").body().contains("action=\"/logout\""), "the menu too, outside the constraint");

        Response logout = browser.post("/logout", "");
        assertEquals(303, logout.status());
        assertEquals("/", URI.create(logout.location()).getPath());

        Response after = browser.get("/kitchen");
        assertTrue(after.body().contains("name=\"j_password\""), "asked to log in again: " + after.body());
        assertFalse(browser.get("/").body().contains("action=\"/logout\""), "no logout button once logged out");
    }

    @Test
    void theMenuOffersToLogInToAnAnonymousVisitor() throws IOException {
        Response menu = new Browser().get("/");

        assertTrue(menu.body().contains("href=\"/login\""), menu.body());
        assertFalse(menu.body().contains("action=\"/logout\""), menu.body());
    }

    private record Response(int status, String location, String body) {
    }

    /** An HTTP client that keeps the session cookie and never follows a redirect on its own. */
    private static final class Browser {

        private String sessionCookie;

        Response get(String path) throws IOException {
            return send("GET", path, null);
        }

        Response post(String path, String form) throws IOException {
            return send("POST", path, form);
        }

        Response login(String user, String password) throws IOException {
            return post("/j_security_check", "j_username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                    + "&j_password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
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
            return new Response(status, connection.getHeaderField("Location"), body);
        }
    }
}
