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

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Everything the console answers on its own listener, mounted at its root: read-only, and for the console's own
 * address only.
 *
 * <ul>
 *   <li>a request whose {@code Host} is not the console's gets {@code 403} ({@link HostGuard});</li>
 *   <li>any method but {@code GET} and {@code HEAD} gets {@code 405}: nothing on the console changes anything;</li>
 *   <li>{@code /api/snapshot} is the {@link Snapshot}; every other path is the page, its static files.</li>
 * </ul>
 *
 * <p>Every answer, a refusal included, carries {@code X-Content-Type-Options: nosniff},
 * {@code Referrer-Policy: no-referrer} and {@code Content-Security-Policy: default-src 'self'; frame-ancestors
 * 'none'}, and no CORS header: another site's page may send a request, it cannot read the answer.
 */
final class ConsoleHandler implements Handler {

    /** The path of the snapshot. */
    static final String SNAPSHOT_PATH = "/api/snapshot";

    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'none'";

    private final HostGuard guard;
    private final IntSupplier boundPort;
    private final Handler snapshot;
    private final Handler page;

    /**
     * @param guard     who may be answered
     * @param boundPort the port the console listens on, {@code 0} until it is bound
     * @param snapshot  what answers {@value #SNAPSHOT_PATH}
     * @param page      what serves every other path: the page's static files
     */
    ConsoleHandler(HostGuard guard, IntSupplier boundPort, Handler snapshot, Handler page) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.boundPort = Objects.requireNonNull(boundPort, "boundPort");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.page = Objects.requireNonNull(page, "page");
    }

    @Override
    public Response handle(Request request) throws Exception {
        Response response;
        if (!guard.allows(request.header("Host").orElse(null), boundPort.getAsInt())) {
            response = Response.builder()
                    .status(StatusCode.FORBIDDEN)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body("This host is not the dev console's.")
                    .build();
        } else if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            response = Response.builder().status(StatusCode.METHOD_NOT_ALLOWED).header("Allow", "GET, HEAD").build();
        } else if (SNAPSHOT_PATH.equals(request.pathInfo())) {
            response = snapshot.handle(request);
        } else {
            response = page.handle(request);
        }
        return guarded(response);
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
