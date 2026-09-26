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

import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpInspection;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The MCP inspector of the {@code mcp} panel (spec §3): an action per item of the {@link McpCatalogue}, which calls
 * the application's own {@code /mcp} through {@link McpClient} and returns what came back as a
 * {@link PanelAction.ActionResult}. Built once per boot by {@link McpLivePanel#start}, dropped by its {@code stop}.
 *
 * <p>When it cannot work it offers no action and says why in the {@code inspector} value: a twin MCP server, a
 * catalogue that cannot be read, or no bound address for {@code /mcp}. The panel's live values keep working.
 *
 * <p>Holds the {@link McpClient} of its endpoint, if any: {@link #close()} must be called once this inspector is
 * replaced or no longer needed, so that a dev reload never leaves the previous boot's {@link java.net.http.HttpClient}
 * running.
 *
 * <p>Each call is recorded in a {@link CallHistory} of 20, its secrets masked (see {@link Secrets}), which the
 * sample publishes with what replays it.
 */
final class McpInspector {

    /** Before {@code start} and after {@code stop}: no action, nothing sampled. */
    static final McpInspector OFF = new McpInspector(null, McpCatalogue.EMPTY, null);
    /** The route of {@code /mcp} has no URL: no listener declared it. */
    static final String NO_ADDRESS = "/mcp has no bound address";
    /** The MCP server is loaded twice ({@code VIDOCQ-MCP-003}): its registries cannot be read. */
    static final String TWIN = "loaded twice";

    private static final System.Logger LOG = System.getLogger(McpInspector.class.getName());

    private final McpClient client;
    private final McpCatalogue catalogue;
    private final String absent;
    private final List<PanelAction> actions;
    private final CallHistory history = new CallHistory();

    private McpInspector(McpClient client, McpCatalogue catalogue, String absent) {
        this.client = client;
        this.catalogue = catalogue;
        this.absent = absent;
        List<PanelAction> built = new ArrayList<>();
        if (client != null) {
            for (McpCatalogue.Item item : catalogue.items()) {
                PanelAction action = action(item);
                if (action != null) {
                    built.add(action);
                }
            }
        }
        this.actions = List.copyOf(built);
    }

    /**
     * The inspector of this boot. Never throws: whatever fails makes an inspector without actions that says why.
     *
     * @param beans      the bean manager of the started container
     * @param inspection what {@code McpInspection} read of it
     * @param urls       the URLs of {@code /mcp} the runtime extension published
     * @param timeout    how long a call may take
     */
    static McpInspector start(BeanManager beans, McpInspection inspection, List<String> urls, Duration timeout) {
        try {
            if (beans == null || inspection == null) {
                return OFF;
            }
            if (inspection.twin()) {
                return new McpInspector(null, McpCatalogue.EMPTY, TWIN);
            }
            McpCatalogue catalogue = McpCatalogue.read(beans);
            if (catalogue.absent() != null) {
                return new McpInspector(null, catalogue, catalogue.absent());
            }
            Optional<URI> endpoint = preferred(urls);
            if (endpoint.isEmpty()) {
                return new McpInspector(null, catalogue, NO_ADDRESS);
            }
            return of(catalogue, endpoint.get(), timeout);
        } catch (RuntimeException | LinkageError unreadable) {
            return new McpInspector(null, McpCatalogue.EMPTY,
                    "catalogue unreadable: " + unreadable.getClass().getSimpleName());
        }
    }

    /** An inspector of {@code catalogue} calling {@code endpoint}. */
    static McpInspector of(McpCatalogue catalogue, URI endpoint, Duration timeout) {
        return new McpInspector(new McpClient(endpoint, timeout), catalogue, null);
    }

    /** A loopback URL when there is one ({@code localhost}, {@code 127.*}, {@code [::1]}), else the first. */
    static Optional<URI> preferred(List<String> urls) {
        List<URI> uris = new ArrayList<>();
        for (String url : urls) {
            try {
                uris.add(URI.create(url));
            } catch (IllegalArgumentException malformed) {
                // a URL the report printed but no client could call: skipped
            }
        }
        return uris.stream().filter(McpInspector::loopback).findFirst().or(() -> uris.stream().findFirst());
    }

    private static boolean loopback(URI uri) {
        String host = uri.getHost();
        return host != null && (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]"));
    }

    /** The actions, in catalogue order; none when the inspector cannot work. */
    List<PanelAction> actions() {
        return actions;
    }

    /** The calls of this boot. */
    CallHistory history() {
        return history;
    }

    /** Writes {@code inspector}: what it offers and where it calls, or why it offers nothing. */
    void sample(PanelSample out) {
        if (client == null) {
            if (absent != null) {
                out.absent("inspector", absent);
            }
            return;
        }
        out.text("inspector", catalogue.summary() + " at " + client.endpoint());
        history.writeTo(out);
    }

    /**
     * Closes the underlying {@link McpClient}, if any; a no-op for {@link #OFF} or an inspector that could not
     * work. Called by {@link McpLivePanel#stop()} and before a new inspector replaces this one, so that a dev
     * reload during a running call aborts it instead of leaving its {@link java.net.http.HttpClient} running.
     */
    void close() {
        if (client != null) {
            client.close();
        }
    }

    /** The action of {@code item}; {@code null}, and logged at DEBUG, when the SPI refuses what it would declare. */
    private PanelAction action(McpCatalogue.Item item) {
        try {
            McpCatalogue.Kind kind = item.kind();
            List<PanelAction.Argument> arguments = kind.argument() == null ? List.of()
                    : List.of(argument(kind, item.schema()));
            return new PanelAction(item.id(), item.label(), item.confirmation(), arguments,
                    given -> call(item, given), kind.group(), item.description());
        } catch (IllegalArgumentException refused) {
            LOG.log(System.Logger.Level.DEBUG, "MCP inspector: no action for " + item.target(), refused);
            return null;
        }
    }

    /** Its json argument; an open object when the SPI refuses the item's own schema. */
    private static PanelAction.Argument argument(McpCatalogue.Kind kind, String schema) {
        try {
            return PanelAction.Argument.json(kind.argument(), kind.argumentLabel(), schema);
        } catch (IllegalArgumentException unusable) {
            return PanelAction.Argument.json(kind.argument(), kind.argumentLabel(), McpCatalogue.OPEN_SCHEMA);
        }
    }

    /**
     * Calls {@code item} with the arguments the console checked, and records the call, its secrets masked.
     *
     * @param item  what to call
     * @param given its json argument's text by name, or nothing for a fixed resource
     * @return what came back, the summary and the details scrubbed of the secrets' values
     */
    PanelAction.ActionResult call(McpCatalogue.Item item, Map<String, String> given) {
        long start = System.nanoTime();
        String argumentName = item.kind().argument();
        JsonObject values = argumentName == null ? JsonValue.EMPTY_JSON_OBJECT : parse(given.get(argumentName));
        Set<String> secrets = Secrets.values(values);
        PanelAction.ActionResult sent = send(item, values, secrets);
        PanelAction.ActionResult result = new PanelAction.ActionResult(Secrets.scrub(sent.summary(), secrets),
                sent.contentType(), sent.body(), sent.error(), sent.details());
        JsonObject masked = (JsonObject) Secrets.mask(values);
        history.add(new CallHistory.Call(System.currentTimeMillis(), item.id(), item.label(),
                argumentName == null ? "" : masked.toString(), result.summary(), result.error(),
                (System.nanoTime() - start) / 1_000_000, result.details(), replay(item.id(), argumentName, masked)));
        return result;
    }

    private PanelAction.ActionResult send(McpCatalogue.Item item, JsonObject values, Set<String> secrets) {
        JsonObjectBuilder params = Json.createObjectBuilder();
        String method;
        String name;
        Map<String, String> headers = Map.of();
        switch (item.kind()) {
            case TOOL -> {
                method = "tools/call";
                name = item.target();
                params.add("name", name).add("arguments", values);
                headers = McpClient.paramHeaders(item.headerDesignations(), values);
            }
            case PROMPT -> {
                method = "prompts/get";
                name = item.target();
                params.add("name", name).add("arguments", values);
            }
            case RESOURCE -> {
                method = "resources/read";
                name = item.target();
                params.add("uri", name);
            }
            default -> {
                method = "resources/read";
                name = UriTemplates.expand(item.target(), values);
                params.add("uri", name);
            }
        }
        JsonObject request = client.request(method, params.build());
        try {
            McpClient.Exchange exchange = client.send(request, name, headers);
            return McpResults.of(item.kind(), exchange, details(request, exchange, secrets));
        } catch (McpTransportException failed) {
            return McpResults.transport(failed.getMessage(), details(request, null, secrets));
        } catch (RuntimeException unexpected) {
            return McpResults.transport("/mcp call failed: " + unexpected.getClass().getSimpleName(),
                    details(request, null, secrets));
        }
    }

    /**
     * The exchange as the page shows it, secrets masked by name and scrubbed by value: the request, and the HTTP
     * status, the SSE events and the response.
     */
    private static String details(JsonObject request, McpClient.Exchange exchange, Set<String> secrets) {
        JsonObjectBuilder out = Json.createObjectBuilder().add("request", Secrets.mask(request));
        if (exchange != null) {
            out.add("status", exchange.status());
            if (!exchange.events().isEmpty()) {
                JsonArrayBuilder events = Json.createArrayBuilder();
                exchange.events().forEach(event -> events.add(Secrets.mask(event)));
                out.add("events", events);
            }
            out.add("response", Secrets.mask(exchange.response()));
        }
        return Secrets.scrub(out.build().toString(), secrets);
    }

    /**
     * The replay cell of a call: its action id, a space, and its masked arguments by name, which the page puts back
     * in the form; empty past {@link PanelSample#MAX_REPLAY_CELL} characters.
     */
    private static String replay(String id, String argumentName, JsonObject masked) {
        JsonObject values = argumentName == null ? JsonValue.EMPTY_JSON_OBJECT
                : Json.createObjectBuilder().add(argumentName, masked).build();
        String cell = id + " " + values;
        return cell.length() > PanelSample.MAX_REPLAY_CELL ? "" : cell;
    }

    /** The console checked that {@code text} is one JSON object; {@code null} reads as an empty one. */
    private static JsonObject parse(String text) {
        if (text == null) {
            return JsonValue.EMPTY_JSON_OBJECT;
        }
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        }
    }
}
