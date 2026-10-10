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
package io.vidocq.heisenberg.it.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drives each Fault Tolerance policy inside the server and answers with what happened, so the test
 * checks the outcome over HTTP. Every answer is the name of an outcome, or of the unexpected exception.
 */
@Path("/ft")
@RequestScoped
@Produces(MediaType.TEXT_PLAIN)
public class FaultToleranceResource {

    @Inject
    RetryService retry;

    @Inject
    TimeoutService timeout;

    @Inject
    CircuitBreakerService circuitBreaker;

    @Inject
    FallbackService fallback;

    @Inject
    BulkheadService bulkhead;

    @Inject
    RecordingFtMetricsRecorder recorder;

    @GET
    @Path("/retry")
    public String retry() {
        return retry.flaky();
    }

    @GET
    @Path("/configured")
    public String configured() {
        try {
            retry.configured();
            return "no failure";
        } catch (IllegalStateException expected) {
            return "calls=" + retry.configuredCalls();
        }
    }

    @GET
    @Path("/timeout")
    public String timeout() throws InterruptedException {
        try {
            return timeout.slow();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    @GET
    @Path("/circuit-breaker")
    public String circuitBreaker() {
        StringBuilder outcomes = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreaker.failing();
            } catch (RuntimeException e) {
                outcomes.append(i == 0 ? "" : ",").append(e.getClass().getSimpleName());
            }
        }
        return outcomes.toString();
    }

    @GET
    @Path("/fallback")
    public String fallback() {
        return fallback.primary("liberty");
    }

    @GET
    @Path("/bulkhead")
    public String bulkhead() throws Exception {
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                return bulkhead.guarded();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            if (!bulkhead.awaitEntered()) {
                return "first call never entered";
            }
            try {
                return bulkhead.guarded();
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        } finally {
            bulkhead.release();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @GET
    @Path("/metric-classes")
    public String metricClasses() {
        return String.join(",", new TreeSet<>(recorder.beanClasses()));
    }
}
