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
package io.vidocq.runtime.it.foyservlet;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Foy's servlet built-in beans (Vidocq/foy#107) in the assembled runtime, on the module path: a servlet injects the
 * {@code HttpServletRequest}, the {@code HttpSession} and the {@code ServletContext}, and an application
 * {@code ServletContextListener} uses the injected context in {@code contextInitialized}. Here the build compatible
 * extensions run at start (this module is not built with {@code vidocq:generate}); {@code CreateThenBuildIT} in
 * {@code vidocq-runtime-it-create} starts a packaged application on the extensions frozen at build.
 */
class FoyServletBuiltInBeansTest {

    private static final String CONTEXT_PATH = "/shop";
    private static final String WHO_AM_I = CONTEXT_PATH + "/whoami";
    private static final String STARTED = "started in " + CONTEXT_PATH;
    private static final List<String> KEYS = List.of("vidocq.chappe.listener.default.host",
            "vidocq.chappe.listener.default.port", "vidocq.servlet.context-path");

    private static RuntimeGenerationWatch runtimeGeneration;
    private static VidocqBootstrap bootstrap;
    private static String base;

    @BeforeAll
    static void boot() {
        runtimeGeneration = RuntimeGenerationWatch.watch();
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", "0");
        System.setProperty("vidocq.servlet.context-path", CONTEXT_PATH);
        try (BoundPort port = BoundPort.watch()) {
            bootstrap = VidocqBootstrap.create().configure().start();
            base = "http://127.0.0.1:" + port.port();
        }
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        KEYS.forEach(System::clearProperty);
        if (runtimeGeneration != null) runtimeGeneration.close();
    }

    @Test
    void theInjectedRequestIsTheOneServedAndTheContextIsTheApplicationsFromItsStart() throws Exception {
        try (HttpClient alice = cookieClient()) {
            HttpResponse<String> r = whoAmI(alice, "alice");

            assertEquals(200, r.statusCode(), r::body);
            assertEquals(String.join("|", WHO_AM_I, "alice", "1", "same session", CONTEXT_PATH, STARTED), r.body());
        }
    }

    @Test
    void theInjectedSessionKeepsItsAttributeAcrossTheRequestsOfOneClient() throws Exception {
        try (HttpClient alice = cookieClient(); HttpClient bob = cookieClient()) {
            assertEquals(String.join("|", WHO_AM_I, "alice", "1", "same session", CONTEXT_PATH, STARTED),
                    whoAmI(alice, "alice").body());
            assertEquals(String.join("|", WHO_AM_I, "bob", "1", "same session", CONTEXT_PATH, STARTED),
                    whoAmI(bob, "bob").body());
            assertEquals(String.join("|", WHO_AM_I, "alice", "2", "same session", CONTEXT_PATH, STARTED),
                    whoAmI(alice, "alice").body());
        }
    }

    /**
     * The client proxies of the three beans come from Foy's build: the container generates none at run time.
     * {@link RuntimeGenerationWatchTest} shows that the watch sees one when the container does.
     */
    @Test
    void theContainerGeneratesNoClassForTheBeans() throws Exception {
        try (HttpClient carol = cookieClient()) {
            assertEquals(200, whoAmI(carol, "carol").statusCode());
        }

        assertEquals(List.of(), runtimeGeneration.warnings());
    }

    private static HttpResponse<String> whoAmI(HttpClient client, String caller) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + WHO_AM_I)).header("X-Caller", caller).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }
}
