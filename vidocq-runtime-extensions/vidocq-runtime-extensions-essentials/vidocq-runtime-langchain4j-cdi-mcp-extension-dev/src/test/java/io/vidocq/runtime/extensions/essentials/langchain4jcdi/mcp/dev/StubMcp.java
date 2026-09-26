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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.cdi.mcp.server.transport.McpEraDetector;
import dev.langchain4j.cdi.mcp.server.transport.McpJsonRpcParser;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A stand-in for the application's {@code /mcp}, on the loopback and an ephemeral port: it records every request,
 * checks its headers and {@code _meta} with langchain4j-cdi's own {@link McpEraDetector}, as the real endpoint does
 * before anything else, and answers what the test asked for.
 */
final class StubMcp implements AutoCloseable {

    /** What the stub answers. */
    record Answer(int status, String contentType, String body, long delayMillis) {}

    /** A request as it arrived: its headers, case-insensitive, and its body. */
    record Received(Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<JsonObject, Answer> responder = request -> result(request, "{}");
    private volatile String rejected;

    private StubMcp(HttpServer server) {
        this.server = server;
    }

    static StubMcp start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubMcp stub = new StubMcp(server);
        server.createContext("/mcp", stub::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return stub;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    void respond(Function<JsonObject, Answer> next) {
        responder = next;
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    /** Why {@code McpEraDetector} refused the last request, or {@code null} when it accepted every one. */
    String rejected() {
        return rejected;
    }

    /** A JSON answer holding {@code result} for {@code request}. */
    static Answer result(JsonObject request, String result) {
        return new Answer(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}", 0);
    }

    /** A JSON-RPC error for {@code request}, with the HTTP status the real endpoint uses for it. */
    static Answer error(JsonObject request, int status, int code, String message) {
        return new Answer(status, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                + ",\"error\":{\"code\":" + code + ",\"message\":" + Json.createValue(message) + "}}", 0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
        received.add(new Received(headers, body));
        JsonObject request = Json.createReader(new StringReader(body)).readObject();
        try {
            McpEraDetector.detect(McpJsonRpcParser.parseRequest(request), headers::get);
        } catch (RuntimeException refused) {
            rejected = refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
        Answer answer = responder.apply(request);
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", answer.contentType());
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
