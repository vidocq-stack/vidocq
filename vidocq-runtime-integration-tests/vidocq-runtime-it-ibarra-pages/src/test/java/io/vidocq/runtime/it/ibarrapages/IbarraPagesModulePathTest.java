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
package io.vidocq.runtime.it.ibarrapages;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vidocq#232: the assembled runtime with the Ibarra extension, on the module path, serves the JSP pages that
 * vidocq:generate compiled into the application module.
 */
class IbarraPagesModulePathTest {

    private static VidocqBootstrap bootstrap;
    private static String base;

    @BeforeAll
    static void boot() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", Integer.toString(port));
        base = "http://127.0.0.1:" + port;
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_pages_were_compiled_into_the_application_module() {
        Module application = IbarraPagesModulePathTest.class.getModule();
        assertTrue(application.isNamed());
        assertTrue(application.getDescriptor().provides().stream()
                        .anyMatch(p -> p.service().equals("io.vidocq.ibarra.spi.PageIndex")),
                "vidocq:generate adds the page index to the module descriptor");
    }

    @Test
    void a_page_evaluates_an_expression() throws Exception {
        HttpResponse<String> r = get("/hello.jsp?x=Ada");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals("Hello Ada!", r.body().strip());
    }

    @Test
    void a_page_calls_a_tag_library_function() throws Exception {
        HttpResponse<String> r = get("/upper.jsp?x=ada");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals("ADA", r.body().strip());
    }

    @Test
    void a_servlet_forwards_a_request_attribute_to_a_page_under_web_inf() throws Exception {
        HttpResponse<String> r = get("/greet?name=Grace");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals("Greetings, Grace.", r.body().strip());
    }

    @Test
    void a_page_under_web_inf_is_out_of_the_client_reach() throws Exception {
        assertEquals(404, get("/WEB-INF/views/greet.jsp").statusCode());
    }

    @Test
    void a_missing_page_answers_404() throws Exception {
        assertEquals(404, get("/nowhere.jsp").statusCode());
    }
}
