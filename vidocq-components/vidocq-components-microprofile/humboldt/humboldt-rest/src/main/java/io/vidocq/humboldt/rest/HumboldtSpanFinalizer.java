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
package io.vidocq.humboldt.rest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.annotation.Priority;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ExceptionMapper} fallback that records an exception escaping a resource method on the
 * SERVER span started by {@link HumboldtServerRequestFilter}.
 *
 * <p>The span stays open: {@link HumboldtServerResponseFilter} ends it, with the status code of the
 * mapped response. Jakarta REST runs the response filters after an {@code ExceptionMapper}
 * (§6.7.4), and every runtime Humboldt is tested on does: Cassini, and RESTEasy in Open Liberty.</p>
 *
 * <p>The SERVER span is the current one: the request filter made it current, on the thread that
 * maps the exception, and the response filter closes that scope. The mapper therefore needs no
 * {@code @Context ContainerRequestContext}, which Jakarta REST does not define as injectable into
 * a provider: RESTEasy fails on it, and turned every application exception into an internal
 * error (humboldt#23).</p>
 *
 * <p><b>JAX-RS selection</b>: {@code ExceptionMapper<Throwable>} is as generic as possible — the
 * container only selects it when no more specific application mapper matches. A
 * {@link WebApplicationException} keeps its own response, so a 404 or a 406 stays one; only a 5xx
 * marks the span as an error, as for any response.</p>
 *
 * <p>Any other exception gets what the runtime would give it without this mapper: a bare 500, with no entity, so the
 * client learns nothing of the failure (CWE-209), and an ERROR line with the exception for the operator, since
 * mapping it skips the runtime's own (BUG-20261114-01).</p>
 */
@Provider
@jakarta.enterprise.context.Dependent
@Priority(jakarta.ws.rs.Priorities.USER + 1000)
public class HumboldtSpanFinalizer implements ExceptionMapper<Throwable> {

    private static final AtomicReference<System.Logger> LOG =
            new AtomicReference<>(System.getLogger(HumboldtSpanFinalizer.class.getName()));

    /** Replaces the logger the unmapped exceptions go to, and returns the previous one (tests). */
    static System.Logger useLogger(System.Logger logger) {
        return LOG.getAndSet(logger);
    }

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException wae && wae.getResponse() != null) {
            return wae.getResponse();
        }
        recordOnServerSpan(exception);
        // What the runtime does without this mapper: the operator gets the exception in the log, the client a bare
        // 500 that tells nothing of the failure (CWE-209). Mapping the exception skips the runtime's own log line.
        LOG.get().log(System.Logger.Level.ERROR,
                "A resource method threw an exception no ExceptionMapper handles; the client gets a 500", exception);
        return Response.serverError().build();
    }

    private static void recordOnServerSpan(Throwable t) {
        Span span = Span.current();
        if (!span.isRecording()) return;
        span.recordException(t);
        span.setStatus(StatusCode.ERROR,
                t.getClass().getSimpleName()
                        + ": " + (t.getMessage() != null ? t.getMessage() : ""));
    }
}
