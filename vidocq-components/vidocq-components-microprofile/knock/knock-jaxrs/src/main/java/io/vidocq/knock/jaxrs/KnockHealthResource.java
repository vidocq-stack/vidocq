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
package io.vidocq.knock.jaxrs;

import io.vidocq.knock.runtime.HealthReport;
import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Jakarta REST resource exposing the MicroProfile Health 4.0 §3 endpoints:
 *
 * <ul>
 *   <li>{@code GET /health}         — aggregate of all checks ({@link ProbeType#ALL})</li>
 *   <li>{@code GET /health/live}    — probes {@link ProbeType#LIVENESS}</li>
 *   <li>{@code GET /health/ready}   — probes {@link ProbeType#READINESS}</li>
 *   <li>{@code GET /health/started} — probes {@link ProbeType#STARTUP}</li>
 * </ul>
 *
 * <p>Spec §3 : HTTP 200 if status is UP, HTTP 503 if DOWN. JSON body compliant with §3.1
 * produced by {@link KnockHealthService} (Jakarta JSON-P, champollion at runtime).</p>
 *
 * <p>Standard JAX-RS API only — no internal Cassini class imports.</p>
 */
@ApplicationScoped
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class KnockHealthResource {

    @Inject
    HealthCheckRegistry registry;

    /**
     * No-args constructor required by CDI for {@code @ApplicationScoped} beans
     * (client proxy instantiation).
     */
    public KnockHealthResource() {
        // CDI proxy; the {@code registry} field will be injected later.
    }

    /**
     * Injection / test constructor — direct instantiation with a given registry.
     *
     * @param registry the registry to query (non-null)
     */
    public KnockHealthResource(HealthCheckRegistry registry) {
        this.registry = registry;
    }

    /**
     * {@code GET /health} — aggregate of all checks (§3, {@link ProbeType#ALL}).
     */
    @GET
    public Response getHealth() {
        return toResponse(ProbeType.ALL);
    }

    /**
     * {@code GET /health/live} — probes {@link ProbeType#LIVENESS} (§3).
     */
    @GET
    @Path("live")
    public Response getLiveness() {
        return toResponse(ProbeType.LIVENESS);
    }

    /**
     * {@code GET /health/ready} — probes {@link ProbeType#READINESS} (§3).
     */
    @GET
    @Path("ready")
    public Response getReadiness() {
        return toResponse(ProbeType.READINESS);
    }

    /**
     * {@code GET /health/started} — probes {@link ProbeType#STARTUP} (§3).
     */
    @GET
    @Path("started")
    public Response getStartup() {
        return toResponse(ProbeType.STARTUP);
    }

    private Response toResponse(ProbeType type) {
        HealthReport report = new KnockHealthService(registry).report(type);
        return Response.status(report.httpStatus())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(report.json())
                .build();
    }
}
