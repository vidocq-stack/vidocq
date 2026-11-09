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
package io.vidocq.runtime.it.bodonijstl;

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
class BodoniJstlModulePathTest {

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
        Module application = BodoniJstlModulePathTest.class.getModule();
        assertTrue(application.isNamed());
        assertTrue(application.getDescriptor().provides().stream()
                        .anyMatch(p -> p.service().equals("io.vidocq.ibarra.spi.PageIndex")),
                "vidocq:generate adds the page index to the module descriptor");
    }

    @Test
    void peano_runs_with_reflection_denied() {
        assertEquals("deny", System.getProperty("peano.reflection"));
    }

    @Test
    void core_tags_and_functions_render() throws Exception {
        HttpResponse<String> r = get("/core.jsp?who=ada");

        assertEquals(200, r.statusCode(), r.body());
        String body = r.body().replaceAll("\\s+", " ").strip();
        assertEquals("Hello ADA [0:a,][1:b,][2:c.] short &lt;b&gt;", body);
    }

    @Test
    void for_each_reads_var_status_under_denied_reflection() throws Exception {
        // bodoni-peano supplies the LoopTagStatus accessor table the extension brings.
        HttpResponse<String> r = get("/core.jsp?who=grace");

        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("[2:c.]"), r.body());
        assertTrue(r.body().contains("long"), r.body());
    }

    @Test
    void for_each_reads_map_entries_under_denied_reflection() throws Exception {
        HttpResponse<String> r = get("/entries.jsp?k=v");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals("k=v;", r.body().strip());
    }

    @Test
    void formatting_tags_render() throws Exception {
        HttpResponse<String> r = get("/format.jsp");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals("1,234.5|25% 43", r.body().replaceAll("\\s+", " ").strip());
    }

    @Test
    void a_servlet_forwards_a_request_attribute_to_a_page_that_uses_tags() throws Exception {
        HttpResponse<String> r = get("/greet?name=%20Grace%20");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals("Grace", r.body().strip());
    }

    @Test
    void a_page_under_web_inf_is_out_of_the_client_reach() throws Exception {
        assertEquals(404, get("/WEB-INF/views/greet.jsp").statusCode());
    }
}
