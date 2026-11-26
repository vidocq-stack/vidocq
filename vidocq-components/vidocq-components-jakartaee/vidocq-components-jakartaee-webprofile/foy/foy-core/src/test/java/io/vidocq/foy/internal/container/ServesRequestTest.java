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
package io.vidocq.foy.internal.container;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChappeServletBridge#serves}: whether the application has something for a request, asked by a
 * transport that puts several handlers on one prefix (vidocq#802). A request only the container default
 * servlet would take, for a file that does not exist, is not the application's: it goes to the next
 * handler instead of answering 404.
 */
class ServesRequestTest {

    @TempDir Path root;
    private Deployment deployment;

    @BeforeEach
    void files() throws IOException {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/index.html"), "INDEX");
        Files.writeString(root.resolve("plain.txt"), "plain");
    }

    @AfterEach
    void tearDown() {
        if (deployment != null) deployment.close();
    }

    private ChappeServletBridge deploy(ServletDecl... servlets) {
        var b = WebAppModel.builder("/ctx").welcomeFiles(List.of("index.html"));
        for (var s : servlets) b.servlet(s);
        deployment = WebAppDeployer.deploy(b.build(), DeployOptions.defaults(getClass().getClassLoader())
                .withResourceProvider(new WelcomeFilesTest.Provider(root)));
        return (ChappeServletBridge) deployment.handler();
    }

    private static ServletDecl who(String name, String... patterns) {
        return new ServletDecl(name, WelcomeFilesTest.Who.class, WelcomeFilesTest.Who::new, List.of(patterns),
                Map.of(), -1, true);
    }

    @Test
    void aMappedServletAFileAWelcomeFileAndADirectoryRedirectAreServed() {
        ChappeServletBridge bridge = deploy(who("cart", "/cart"));

        assertTrue(bridge.serves(request("/ctx/cart")));
        assertTrue(bridge.serves(request("/ctx/plain.txt")));
        assertTrue(bridge.serves(request("/ctx/docs/")));
        assertTrue(bridge.serves(request("/ctx/docs")));
    }

    @Test
    void aPathOnlyTheContainerDefaultServletWouldTakeIsNotServed() {
        ChappeServletBridge bridge = deploy(who("cart", "/cart"));

        assertFalse(bridge.serves(request("/ctx/items")));
        assertFalse(bridge.serves(request("/ctx/missing.txt")));
        assertFalse(bridge.serves(request("/ctx/")));
        assertFalse(bridge.serves(request("/elsewhere")));
        assertFalse(bridge.serves(request("/ctx/WEB-INF/web.xml")));
    }

    @Test
    void anApplicationDefaultServletServesEveryPath() {
        ChappeServletBridge bridge = deploy(who("all", "/"));

        assertTrue(bridge.serves(request("/ctx/items")));
    }

    private static Request request(String path) {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create(path); }
            @Override public String path() { return path; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return Headers.empty(); }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
        };
    }
}
