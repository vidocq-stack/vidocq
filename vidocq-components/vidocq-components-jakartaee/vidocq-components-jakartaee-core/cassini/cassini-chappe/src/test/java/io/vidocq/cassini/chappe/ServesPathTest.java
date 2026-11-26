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
package io.vidocq.cassini.chappe;

import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.ForwardingRequest;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ChappeHttpAdapter#serves} asks the engine about the path it would route, without dispatching. */
class ServesPathTest {

    /** An engine with a resource at /items only, that records what it was asked and whether it dispatched. */
    private static final class ItemsEngine implements CassiniHttpAdapter {
        final AtomicReference<String> asked = new AtomicReference<>();
        final AtomicReference<String> dispatched = new AtomicReference<>();

        @Override
        public CompletionStage<Void> dispatch(CassiniHttpExchange exchange) {
            dispatched.set(exchange.routingPath());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean hasResourceAt(String routingPath) {
            asked.set(routingPath);
            return routingPath.equals("/items");
        }
    }

    @Test
    void theEngineIsAskedAboutTheRoutingPathAndNothingIsDispatched() {
        var engine = new ItemsEngine();
        var adapter = new ChappeHttpAdapter(engine);

        assertTrue(adapter.serves(request("/items")));
        assertEquals("/items", engine.asked.get());
        assertFalse(adapter.serves(request("/index.html")));
        assertNull(engine.dispatched.get());
    }

    @Test
    void underAMountTheEngineIsAskedAboutThePathBelowThePrefix() {
        var engine = new ItemsEngine();
        Request mounted = new ForwardingRequest() {
            @Override public Request delegate() { return request("/api/items"); }
            @Override public String contextPath() { return "/api"; }
            @Override public String pathInfo() { return "/items"; }
        };

        assertTrue(new ChappeHttpAdapter(engine).serves(mounted));
        assertEquals("/items", engine.asked.get());
    }

    @Test
    void anEngineThatCannotTellServesEveryPath() {
        CassiniHttpAdapter engine = exchange -> CompletableFuture.completedFuture(null);

        assertTrue(new ChappeHttpAdapter(engine).serves(request("/anything")));
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
