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
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientRequestContext;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Ends the CLIENT span of a call that got no response, in error: the Vidocq REST clients (Cyrano, Cassini) hand it
 * the failure of a call whose response filters did not all run (refused connection, timeout, I/O error, interrupt,
 * or a filter that threw), through the {@link HumboldtClientRequestFilter#FAILURE_LISTENER_PROPERTY} request
 * property. Jakarta REST has no such callback, so another client leaves the span open, as before
 * (Vidocq/vidocq#686, BUG-20261102-03).
 *
 * <p>Follows the OpenTelemetry HTTP client semantic conventions for a request that fails before a response:
 * {@code error.type} is the fully qualified class name of the exception, the status is {@link StatusCode#ERROR},
 * and the exception is recorded as an {@code exception} event. The wrapper the client throws
 * ({@link ProcessingException}, {@link CompletionException}, {@link ExecutionException}) is unwrapped, so the span
 * names what went wrong ({@code java.net.http.HttpTimeoutException}, {@code java.net.ConnectException}).</p>
 *
 * <p>The span ends once: by this listener or by {@link HumboldtClientResponseFilter}, whichever claims it first.</p>
 */
final class ClientCallFailureListener implements Consumer<Throwable> {

    private final Span span;
    private final ClientRequestContext requestContext;
    private final AtomicBoolean claimed = new AtomicBoolean();

    ClientCallFailureListener(Span span, ClientRequestContext requestContext) {
        this.span = span;
        this.requestContext = requestContext;
    }

    /** Takes the span for its end: {@code true} for the first caller only. */
    boolean claim() {
        return claimed.compareAndSet(false, true);
    }

    @Override
    public void accept(Throwable failure) {
        if (!claim()) return;
        Throwable cause = unwrap(failure);
        try {
            span.setAttribute(HumboldtClientRequestFilter.ERROR_TYPE, cause.getClass().getName());
            span.recordException(cause);
            span.setStatus(StatusCode.ERROR, cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? ": " + cause.getMessage() : ""));
        } finally {
            span.end();
            requestContext.removeProperty(HumboldtClientRequestFilter.SPAN_PROPERTY);
            requestContext.removeProperty(HumboldtClientRequestFilter.FAILURE_LISTENER_PROPERTY);
        }
    }

    // Identity on purpose: a throwable that is its own cause ends the unwrapping.
    @SuppressWarnings("ReferenceEquality")
    private static Throwable unwrap(Throwable failure) {
        Throwable t = failure == null ? new IllegalStateException("REST client call failed") : failure;
        while ((t instanceof ProcessingException || t instanceof CompletionException
                || t instanceof ExecutionException) && t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
