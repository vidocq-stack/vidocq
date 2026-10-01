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

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;

import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Everything the console answers on its own listener, mounted at its root, for the console's own address only.
 *
 * <ul>
 *   <li>a request whose {@code Host} is not the console's gets {@code 403} ({@link HostGuard});</li>
 *   <li>in a {@code dev} launch, {@code /api/action/<panel>/<action>} runs an action of a panel, behind the checks
 *       below; outside it, that path is nothing special;</li>
 *   <li>any other method but {@code GET} and {@code HEAD} gets {@code 405}: outside a dev launch, the console is
 *       read-only, and a {@code POST} anywhere gets {@code 405} as well;</li>
 *   <li>in a {@code dev} launch, {@code /mcp} is the {@linkplain DevMcp dev MCP}: {@code POST} only
 *       ({@code 405}), {@code application/json} ({@code 415}), no {@code Origin} or the console's own
 *       ({@code 403}), an {@code Accept} that takes JSON ({@code 406}), and no token, since its tools only read;
 *       outside it, that path is nothing special;</li>
 *   <li>{@code /api/language/<panel>/<id>} is a language a panel offers the page's code editor, its JSON as the
 *       panel wrote it, {@code Cache-Control: no-cache}: {@code GET} or {@code HEAD} ({@code 405}), a {@code dev}
 *       launch ({@code 404} in any other), no {@code Origin} or the console's own ({@code 403}), a panel and a
 *       language of those ids ({@code 404}). No token, since it only reads, and never logged;</li>
 *   <li>{@code /api/snapshot} is the {@link Snapshot}; every other path is the page, its static files.</li>
 * </ul>
 *
 * <p>An action request is run only when every check passes, in this order, each failure answering without running
 * anything (ADR 0001): the {@code Host} ({@code 403}); the method, {@code POST} ({@code 405}), and the content type,
 * {@code application/json} ({@code 415}), which a cross-site form cannot send without a CORS preflight the console
 * never answers; the {@code Origin}, present and equal to {@code http://} followed by the {@code Host} the request
 * was let in with, that is the origin of the page served there ({@code 403}); the {@value ConsoleActions#TOKEN_HEADER}
 * header, equal to the token of the boot, which only a page that could read the snapshot knows ({@code 403}). A
 * refusal for the last two is logged as {@value ConsoleActions#CROSS_SITE}. Then {@link ConsoleActions} checks what
 * the request asks for and runs it.
 *
 * <p>Every answer, a refusal included, carries {@code X-Content-Type-Options: nosniff},
 * {@code Referrer-Policy: no-referrer} and {@code Content-Security-Policy: default-src 'self'; frame-ancestors
 * 'none'}, and no CORS header: another site's page may send a request, it cannot read the answer.
 */
final class ConsoleHandler implements Handler {

    /** The path of the snapshot. */
    static final String SNAPSHOT_PATH = "/api/snapshot";
    /** Where a panel's language is, {@code /api/language/<panel>/<id>}. */
    static final String LANGUAGE_PREFIX = "/api/language/";

    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'none'";

    private final HostGuard guard;
    private final IntSupplier boundPort;
    private final Snapshot snapshot;
    private final Handler page;
    private final DevMcp mcp;

    /**
     * @param guard     who may be answered
     * @param boundPort the port the console listens on, {@code 0} until it is bound
     * @param snapshot  what answers {@value #SNAPSHOT_PATH}, and holds the panels and the actions of the boot
     * @param page      what serves every other path: the page's static files
     */
    ConsoleHandler(HostGuard guard, IntSupplier boundPort, Snapshot snapshot, Handler page) {
        this(guard, boundPort, snapshot, page, null);
    }

    /**
     * @param guard     who may be answered
     * @param boundPort the port the console listens on, {@code 0} until it is bound
     * @param snapshot  what answers {@value #SNAPSHOT_PATH}, and holds the panels and the actions of the boot
     * @param page      what serves every other path: the page's static files
     * @param mcp       what answers {@value DevMcp#PATH} in a dev launch, {@code null} in any other
     */
    ConsoleHandler(HostGuard guard, IntSupplier boundPort, Snapshot snapshot, Handler page, DevMcp mcp) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.boundPort = Objects.requireNonNull(boundPort, "boundPort");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.page = Objects.requireNonNull(page, "page");
        this.mcp = mcp;
    }

    @Override
    public Response handle(Request request) throws Exception {
        Response response;
        String host = request.header("Host").orElse(null);
        ConsoleActions actions = snapshot.actions();
        if (!guard.allows(host, boundPort.getAsInt())) {
            response = Response.builder()
                    .status(StatusCode.FORBIDDEN)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body("This host is not the dev console's.")
                    .build();
        } else if (actions != null && request.pathInfo() != null
                && request.pathInfo().startsWith(ConsoleActions.PATH_PREFIX)) {
            response = action(request, host, actions);
        } else if (mcp != null && DevMcp.PATH.equals(request.pathInfo())) {
            response = mcp(request, host, actions);
        } else if (request.pathInfo() != null && request.pathInfo().startsWith(LANGUAGE_PREFIX)) {
            response = language(request, host, actions != null);
        } else if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            response = Response.builder().status(StatusCode.METHOD_NOT_ALLOWED).header("Allow", "GET, HEAD").build();
        } else if (SNAPSHOT_PATH.equals(request.pathInfo())) {
            response = snapshot.handle(request);
        } else {
            response = page.handle(request);
        }
        return guarded(response);
    }

    /** An action request of a dev boot, its {@code Host} already let in: the checks, in the order of ADR 0001. */
    private Response action(Request request, String host, ConsoleActions actions) {
        if (request.method() != HttpMethod.POST) {
            return Response.builder().status(StatusCode.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
        }
        if (!json(request.header("Content-Type").orElse(null))) {
            return ConsoleActions.text(StatusCode.UNSUPPORTED_MEDIA_TYPE, "An action takes application/json.");
        }
        String origin = request.header("Origin").orElse(null);
        if (!sameOrigin(origin, host)) {
            actions.refused("not the console's own origin", origin);
            return ConsoleActions.text(StatusCode.FORBIDDEN, "This origin is not the dev console's.");
        }
        if (!actions.tokenMatches(request.header(ConsoleActions.TOKEN_HEADER).orElse(null))) {
            actions.refused("no valid " + ConsoleActions.TOKEN_HEADER, origin);
            return ConsoleActions.text(StatusCode.FORBIDDEN, "No valid " + ConsoleActions.TOKEN_HEADER
                    + ": reload the page.");
        }
        String rest = request.pathInfo().substring(ConsoleActions.PATH_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1 || rest.indexOf('/', slash + 1) >= 0) {
            return ConsoleActions.text(StatusCode.NOT_FOUND, "No such action.");
        }
        return actions.run(request, snapshot.panel(rest.substring(0, slash)), rest.substring(slash + 1));
    }

    /**
     * A request to the dev MCP of a dev boot, its {@code Host} already let in: the method, {@code POST}
     * ({@code 405}); the content type, {@code application/json} ({@code 415}); the {@code Origin}, which an MCP
     * client that is no browser does not send, and which must otherwise be the console's own ({@code 403}); the
     * {@code Accept} header, which must take {@code application/json} ({@code 406}). No token: the tools only read.
     */
    private Response mcp(Request request, String host, ConsoleActions actions) {
        if (request.method() != HttpMethod.POST) {
            return Response.builder().status(StatusCode.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
        }
        if (!json(request.header("Content-Type").orElse(null))) {
            return ConsoleActions.text(StatusCode.UNSUPPORTED_MEDIA_TYPE, "An MCP request is application/json.");
        }
        String origin = request.header("Origin").orElse(null);
        if (origin != null && !sameOrigin(origin, host)) {
            if (actions != null) {
                actions.refused("MCP request", "not the console's own origin", origin);
            }
            return ConsoleActions.text(StatusCode.FORBIDDEN, "This origin is not the dev console's.");
        }
        if (!acceptsJson(request.headers().all("Accept"))) {
            return ConsoleActions.text(StatusCode.NOT_ACCEPTABLE, "The dev MCP answers application/json only.");
        }
        return mcp.handle(request);
    }

    /**
     * A request for a panel's language, its {@code Host} already let in: the method, {@code GET} or {@code HEAD}
     * ({@code 405}); a dev boot ({@code 404}); the {@code Origin}, which a same-origin {@code GET} does not send, and
     * which must otherwise be the console's own ({@code 403}); a panel and a language of the ids the path names
     * ({@code 404}). Then its JSON, as the panel wrote it. Nothing is logged: it only reads.
     */
    private Response language(Request request, String host, boolean dev) {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            return Response.builder().status(StatusCode.METHOD_NOT_ALLOWED).header("Allow", "GET, HEAD").build();
        }
        if (!dev) {
            return ConsoleActions.text(StatusCode.NOT_FOUND, "No such language.");
        }
        String origin = request.header("Origin").orElse(null);
        if (origin != null && !sameOrigin(origin, host)) {
            return ConsoleActions.text(StatusCode.FORBIDDEN, "This origin is not the dev console's.");
        }
        String rest = request.pathInfo().substring(LANGUAGE_PREFIX.length());
        int slash = rest.indexOf('/');
        PanelEntry panel = slash <= 0 || rest.indexOf('/', slash + 1) >= 0 ? null
                : snapshot.panel(rest.substring(0, slash));
        PanelLanguage language = panel == null ? null : panel.language(rest.substring(slash + 1));
        if (language == null) {
            return ConsoleActions.text(StatusCode.NOT_FOUND, "No such language.");
        }
        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-cache")
                .body(language.json())
                .build();
    }

    /** Whether an {@code Accept} header takes {@code application/json}: itself, {@code application/*}, or all. */
    private static boolean acceptsJson(List<String> accepts) {
        for (String accept : accepts) {
            for (String range : accept.split(",")) {
                int semicolon = range.indexOf(';');
                String type = (semicolon < 0 ? range : range.substring(0, semicolon)).strip();
                if (type.equalsIgnoreCase("application/json") || type.equalsIgnoreCase("application/*")
                        || type.equals("*/*")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code contentType} is {@code application/json}, with parameters or not. */
    private static boolean json(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip();
        return type.equalsIgnoreCase("application/json");
    }

    /**
     * Whether {@code origin} is the origin of the page the console served at {@code host}: {@code http://}, then
     * that very {@code Host}, which {@link HostGuard} already let in. A missing origin, the {@code null} origin of a
     * sandboxed frame or a file, another scheme, another name or another port are not.
     */
    private static boolean sameOrigin(String origin, String host) {
        return origin != null && host != null && !host.isEmpty() && origin.length() == 7 + host.length()
                && origin.regionMatches(true, 0, "http://", 0, 7) && origin.regionMatches(true, 7, host, 0,
                host.length());
    }

    /** {@code response} with the headers that keep other sites out, those it already has kept. */
    private static Response guarded(Response response) {
        Headers.Builder headers = Headers.builder();
        for (Headers.Entry entry : response.headers()) {
            headers.add(entry.name(), entry.value());
        }
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        return Response.builder()
                .status(response.status())
                .headers(headers.build())
                .body(response.body())
                .trailers(response.trailers())
                .build();
    }
}
