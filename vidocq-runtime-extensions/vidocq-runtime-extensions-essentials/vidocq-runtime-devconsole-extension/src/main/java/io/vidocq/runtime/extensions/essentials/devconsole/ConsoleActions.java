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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.PanelAction;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The actions of one dev boot (ADR 0001): the per-boot token the page sends back, the checks of a request that
 * {@link ConsoleHandler} leaves to it, the run itself and the last outcome of each action. Exists in a
 * {@code dev} launch only: outside it the console has none, and its handler no action endpoint.
 *
 * <p>{@link ConsoleHandler} checks, in this order, the {@code Host}, the method and the content type, the
 * {@code Origin}, and the {@linkplain #tokenMatches token}; this class then checks what the request asks for:
 * <ol>
 *   <li>the panel and the action exist, else {@code 404};</li>
 *   <li>the body is at most {@value #MAX_BODY} bytes, {@value #MAX_JSON_BODY} for an action with a json argument,
 *       checked before it is read, else {@code 413};</li>
 *   <li>it is one JSON object of strings whose keys are exactly the declared arguments, each value accepted by its
 *       {@link PanelAction.Argument}, a json argument's value one JSON object, else {@code 400};</li>
 *   <li>no other action of the same panel is running, else {@code 409}.</li>
 * </ol>
 * The action then runs on a virtual thread of its own. The request waits for it up to the time limit, 60 seconds by
 * default: {@code 200 {"result": "<summary>"}} when it returned, with {@code error}, {@code contentType},
 * {@code body} and {@code details} when its {@link PanelAction.ActionResult} has them (ADR 0001, amendment 1),
 * {@code 500 {"error": "<class>"}} when it threw, the simple name of the exception's class and never its message,
 * and {@code 202 {"state": "running"}} past the limit, the action going on; its outcome then shows in the snapshot.
 * Every run is logged on {@code io.vidocq.devconsole}: at INFO, {@code Vidocq dev console: action <panel>/<action>
 * by <address>: <result>}; a failure at WARNING, with the class of the exception only, its stack trace at DEBUG.
 *
 * <p>A request refused for its origin or its token is logged as {@value #CROSS_SITE}, at WARNING, once per origin,
 * reason and boot, {@value #MAX_REFUSALS_LOGGED} distinct ones at most.
 */
final class ConsoleActions {

    /** The prefix of an action's path: {@code /api/action/<panel>/<action>}. */
    static final String PATH_PREFIX = "/api/action/";
    /** The header that carries the per-boot token. */
    static final String TOKEN_HEADER = "X-Vidocq-Console-Token";
    /** The code of an action request refused for its origin or its token. */
    static final String CROSS_SITE = "VIDOCQ-DEVC-006";
    /** The largest body of an action request, in bytes. */
    static final int MAX_BODY = 4096;
    /** The largest body of an action request that has a json argument, in bytes (ADR 0001, amendment 1). */
    static final int MAX_JSON_BODY = 64 * 1024;
    /** How long a request waits for its action by default. */
    static final Duration TIME_LIMIT = Duration.ofSeconds(60);
    /** The most refusals logged per boot: a client that is no browser could vary its origin forever. */
    static final int MAX_REFUSALS_LOGGED = 64;

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    private final String token;
    private final byte[] tokenBytes;
    private final LongSupplier clock;
    private final Duration timeLimit;
    /** The action running, by panel: one at a time per panel. */
    private final Map<String, String> running = new ConcurrentHashMap<>();
    /** The last outcome of each action, by panel and action. */
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Set<String> refusalsLogged = ConcurrentHashMap.newKeySet();

    /**
     * How an action ended.
     *
     * @param text   the summary it returned, cleaned, or the simple name of the class of what it threw
     * @param time   when it ended, by the server's clock, in epoch milliseconds
     * @param ok     {@code false} when it threw
     * @param result what it returned, {@code null} when it threw
     */
    record Outcome(String text, long time, boolean ok, PanelAction.ActionResult result) {

        /** Whether the call went through with an outcome that is an error of its target. */
        boolean error() {
            return result != null && result.error();
        }
    }

    /**
     * @param token     the token of this boot, as {@link #newToken()} draws it
     * @param clock     the server's clock, in epoch milliseconds
     * @param timeLimit how long a request waits for its action
     */
    ConsoleActions(String token, LongSupplier clock, Duration timeLimit) {
        this.token = Objects.requireNonNull(token, "token");
        this.tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit");
    }

    /** A new token: 32 bytes from {@link SecureRandom}, in lowercase hex. */
    static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The token of this boot, which the snapshot gives the page. */
    String token() {
        return token;
    }

    /**
     * Whether {@code presented} is the token of this boot, compared in a time that does not depend on where the two
     * differ.
     *
     * @param presented the value of {@value #TOKEN_HEADER}, or {@code null}
     */
    boolean tokenMatches(String presented) {
        return presented != null && MessageDigest.isEqual(tokenBytes, presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs {@value #CROSS_SITE} for a request refused for its origin or its token, the first time this origin is
     * refused for this reason this boot.
     *
     * @param reason what failed, such as {@code not the console's own origin}
     * @param origin the {@code Origin} header, or {@code null}
     */
    void refused(String reason, String origin) {
        refused("action", reason, origin);
    }

    /**
     * Logs {@value #CROSS_SITE} for a request of {@code kind} refused for its origin, the first time this origin is
     * refused for this reason this boot: the {@linkplain DevMcp dev MCP} shares the rule, and the budget.
     *
     * @param kind   what was asked, {@code action} or {@code MCP request}
     * @param reason what failed, such as {@code not the console's own origin}
     * @param origin the {@code Origin} header, or {@code null}
     */
    void refused(String kind, String reason, String origin) {
        String shown = origin == null ? "(none)" : "'" + Texts.clean(origin) + "'";
        if (refusalsLogged.size() >= MAX_REFUSALS_LOGGED
                || !refusalsLogged.add(kind + "\u0000" + reason + "\u0000" + shown)) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "[" + CROSS_SITE + "] Dev console " + kind + " refused, origin " + shown
                + ": " + reason);
    }

    /** The last outcome of {@code action} of {@code panel} this boot, or {@code null}. */
    Outcome outcome(String panel, String action) {
        return outcomes.get(panel + "\u0000" + action);
    }

    /** Whether {@code action} of {@code panel} is running. */
    boolean running(String panel, String action) {
        return action.equals(running.get(panel));
    }

    /**
     * Runs the action a request that passed the handler's checks asks for, once it passed the checks of its own.
     *
     * @param request  the request, its body not read yet
     * @param panel    the panel it names, or {@code null} when there is none of that id
     * @param actionId the action it names
     * @return the answer
     */
    Response run(Request request, PanelEntry panel, String actionId) {
        PanelAction action = panel == null ? null : panel.action(actionId);
        if (action == null) {
            return text(StatusCode.NOT_FOUND, "No such action.");
        }
        int max = hasJson(action) ? MAX_JSON_BODY : MAX_BODY;
        String body;
        try {
            body = body(request, max);
        } catch (TooLarge tooLarge) {
            return text(StatusCode.PAYLOAD_TOO_LARGE, "The body is larger than " + max + " bytes.");
        } catch (IOException | RuntimeException unreadable) {
            return text(StatusCode.BAD_REQUEST, "The body is not UTF-8 text.");
        }
        Map<String, String> arguments;
        try {
            arguments = arguments(action, JsonStrings.object(body));
        } catch (IllegalArgumentException refused) {
            return text(StatusCode.BAD_REQUEST, refused.getMessage());
        }
        String panelId = panel.id();
        if (running.putIfAbsent(panelId, actionId) != null) {
            return json(StatusCode.CONFLICT, new JsonWriter().beginObject().name("state").value("running")
                    .name("action").value(Texts.clean(running.getOrDefault(panelId, ""))).endObject().toString());
        }
        String by = address(request.remoteAddress());
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        try {
            Thread.ofVirtual().name("vidocq-devconsole-action-" + panelId + "/" + actionId)
                    .start(() -> done.complete(execute(panelId, action, arguments, by)));
        } catch (RuntimeException | Error notStarted) {
            running.remove(panelId, actionId);
            throw notStarted;
        }
        try {
            Outcome outcome = done.get(timeLimit.toMillis(), TimeUnit.MILLISECONDS);
            return outcome.ok()
                    ? json(StatusCode.OK, answer(outcome))
                    : json(StatusCode.INTERNAL_SERVER_ERROR, new JsonWriter().beginObject().name("error")
                            .value(outcome.text()).endObject().toString());
        } catch (TimeoutException | ExecutionException stillRunning) {
            return stillRunning();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return stillRunning();
        }
    }

    /**
     * The answer of an action that returned: {@code result}, its summary, then {@code error}, {@code contentType},
     * {@code body} and {@code details}, each only when set, so that an action returning one line answers
     * {@code {"result": "..."}} as it always did.
     */
    static String answer(Outcome outcome) {
        JsonWriter out = new JsonWriter().beginObject().name("result").value(outcome.text());
        PanelAction.ActionResult result = outcome.result();
        if (result.error()) {
            out.name("error").value(true);
        }
        if (result.body() != null) {
            out.name("contentType").value(result.contentType()).name("body").value(result.body());
        }
        if (result.details() != null) {
            out.name("details").value(result.details());
        }
        return out.endObject().toString();
    }

    /** Whether {@code action} takes a json argument, which allows it a body of {@value #MAX_JSON_BODY} bytes. */
    private static boolean hasJson(PanelAction action) {
        return action.arguments().stream().anyMatch(argument -> argument.schema() != null);
    }

    /** Runs {@code action}, records and logs its outcome, and frees its panel for the next one. */
    private Outcome execute(String panelId, PanelAction action, Map<String, String> arguments, String by) {
        String name = Texts.clean(panelId) + "/" + action.id();
        Outcome outcome;
        try {
            PanelAction.ActionResult result = action.call().apply(arguments);
            if (result == null) {
                result = PanelAction.ActionResult.of(null);
            }
            outcome = new Outcome(Texts.clean(result.summary()), clock.getAsLong(), true, result);
            LOG.log(System.Logger.Level.INFO, "Vidocq dev console: action " + name + " by " + by + ": "
                    + outcome.text());
        } catch (Throwable failure) {
            outcome = new Outcome(Snapshot.className(failure), clock.getAsLong(), false, null);
            LOG.log(System.Logger.Level.WARNING, "Vidocq dev console: action " + name + " by " + by + " failed: "
                    + outcome.text());
            LOG.log(System.Logger.Level.DEBUG, "Dev console action " + name + " failed", failure);
            if (failure instanceof VirtualMachineError fatal) {
                record(panelId, action.id(), outcome);
                throw fatal;
            }
        }
        record(panelId, action.id(), outcome);
        return outcome;
    }

    private void record(String panelId, String actionId, Outcome outcome) {
        outcomes.put(panelId + "\u0000" + actionId, outcome);
        running.remove(panelId, actionId);
    }

    /**
     * The arguments of {@code action}, checked: exactly the names it declared, each value accepted.
     *
     * @throws IllegalArgumentException saying which argument is wrong, never quoting its value
     */
    private static Map<String, String> arguments(PanelAction action, Map<String, String> sent) {
        Map<String, String> checked = new HashMap<>();
        for (PanelAction.Argument argument : action.arguments()) {
            String value = sent.get(argument.name());
            if (value == null) {
                throw new IllegalArgumentException("Argument " + argument.name() + " is missing.");
            }
            if (!argument.accepts(value)) {
                throw new IllegalArgumentException("Argument " + argument.name() + (argument.schema() != null
                        ? " is not a JSON object." : " has a value it does not accept."));
            }
            checked.put(argument.name(), value);
        }
        if (sent.size() != checked.size()) {
            throw new IllegalArgumentException("The body holds an argument the action does not declare.");
        }
        return Map.copyOf(checked);
    }

    /**
     * The body of {@code request} as UTF-8 text.
     *
     * @param max the most bytes it may hold
     * @throws TooLarge when it holds more
     * @throws IllegalArgumentException when it is not UTF-8
     */
    static String body(Request request, int max) throws IOException {
        long declared = request.body().contentLength();
        if (declared > max) {
            throw new TooLarge();
        }
        byte[] bytes;
        try (InputStream in = request.body().asInputStream()) {
            bytes = in.readNBytes(max + 1);
        }
        if (bytes.length > max) {
            throw new TooLarge();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("not UTF-8", malformed);
        }
    }

    /** A body past its limit. */
    static final class TooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TooLarge() {
            super(null, null, false, false);
        }
    }

    private static String address(InetSocketAddress remote) {
        if (remote == null || remote.getAddress() == null) {
            return "an unknown address";
        }
        return remote.getAddress().getHostAddress();
    }

    private static Response stillRunning() {
        return json(StatusCode.ACCEPTED, new JsonWriter().beginObject().name("state").value("running").endObject()
                .toString());
    }

    private static Response json(StatusCode status, String body) {
        return Response.builder()
                .status(status)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")
                .body(body)
                .build();
    }

    /** A refusal, in plain text: what was wrong, never what the request carried. */
    static Response text(StatusCode status, String message) {
        return Response.builder()
                .status(status)
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Cache-Control", "no-store")
                .body(message)
                .build();
    }
}
