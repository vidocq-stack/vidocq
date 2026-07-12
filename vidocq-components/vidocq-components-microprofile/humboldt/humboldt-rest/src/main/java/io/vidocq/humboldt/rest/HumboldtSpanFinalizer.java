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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * {@link ExceptionMapper} fallback that terminates the span started by
 * {@link HumboldtServerRequestFilter} when an exception propagates out of the
 * resource method — a case where {@link HumboldtServerResponseFilter}
 * is not called by the JAX-RS container.
 *
 * <p><b>Why necessary</b>: per JAX-RS spec §10.2.7, {@code ContainerResponseFilter}
 * instances MUST be invoked EVEN when an {@code ExceptionMapper} transforms the
 * exception into a {@code Response}. Certain containers (including Cassini at the
 * time this was written — see {@code Invoker.java:365}) short-circuit this
 * flow and go directly from {@code ExceptionMapper.toResponse()} to marshalling
 * without invoking the response filters. Without this fallback mapper the SERVER
 * span created by the request filter would never be {@code end()}'d and would
 * remain invisible in the exporter.</p>
 *
 * <p><b>JAX-RS selection</b>: {@code ExceptionMapper<Throwable>} is as generic as
 * possible — it is only selected by the container if NO more-specific application
 * mapper matches the exception. Applications can therefore provide their own
 * {@code ExceptionMapper<UserSpecificException>} without collision. For that case
 * (a user mapper that matches), the span will (should) be terminated by the normal
 * response filter — unless the container also has the Cassini bug, in which case
 * the application must explicitly end the span itself.</p>
 *
 * <p>Once the Cassini bug is fixed (response filters called after ExceptionMapper),
 * this code becomes redundant but harmless — the span has already been end()'d by
 * the response filter, the {@code instanceof Span} check will return false (the
 * property will have been removed), and the mapper simply returns a generic 500.</p>
 */
@Provider
@Priority(jakarta.ws.rs.Priorities.USER + 1000)
public class HumboldtSpanFinalizer implements ExceptionMapper<Throwable> {

    private static final Logger LOG = System.getLogger(HumboldtSpanFinalizer.class.getName());
    private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
            AttributeKey.longKey("http.response.status_code");

    @Context
    ContainerRequestContext requestContext;

    @Override
    public Response toResponse(Throwable exception) {
        finalizeSpan(exception);
        return Response.status(500)
                .entity(exception.getClass().getSimpleName()
                        + ": " + (exception.getMessage() != null ? exception.getMessage() : ""))
                .type("text/plain")
                .build();
    }

    private void finalizeSpan(Throwable t) {
        if (requestContext == null) return;
        Object spanObj = requestContext.getProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        Object scopeObj = requestContext.getProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
        if (!(spanObj instanceof Span span)) return;

        span.recordException(t);
        span.setStatus(StatusCode.ERROR,
                t.getClass().getSimpleName()
                        + ": " + (t.getMessage() != null ? t.getMessage() : ""));
        span.setAttribute(HTTP_RESPONSE_STATUS_CODE, 500L);
        try {
            if (scopeObj instanceof Scope scope) {
                scope.close();
            }
        } finally {
            span.end();
        }
        requestContext.removeProperty(HumboldtServerRequestFilter.SPAN_PROPERTY);
        requestContext.removeProperty(HumboldtServerRequestFilter.SCOPE_PROPERTY);
        LOG.log(Level.DEBUG, "Humboldt SERVER span ended via ExceptionMapper fallback: {0}",
                t.getClass().getSimpleName());
    }
}
