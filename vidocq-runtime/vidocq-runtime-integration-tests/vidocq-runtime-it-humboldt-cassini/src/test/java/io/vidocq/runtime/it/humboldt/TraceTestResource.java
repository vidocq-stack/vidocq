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
package io.vidocq.runtime.it.humboldt;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS test resource for the 4 Humboldt scenarios:
 * <ul>
 *   <li>{@code GET /trace/work} — calls a service annotated {@code @WithSpan}.
 *       Must produce 2 spans: SERVER (filter humboldt-rest) + INTERNAL "traced.work" (BCE)</li>
 *   <li>{@code GET /trace/plain} — calls an unannotated service.
 *       Must produce 1 SERVER span + NO INTERNAL span</li>
 *   <li>{@code GET /trace/boom} — calls a service that throw.
 *       Must produce 2 spans with status ERROR</li>
 * </ul>
 */
@Path("/trace")
@Produces(MediaType.TEXT_PLAIN)
@RequestScoped
public class TraceTestResource {

    @Inject
    TraceTestService service;

    @GET
    @Path("/work")
    public String work() {
        return service.doWork();
    }

    @GET
    @Path("/plain")
    public String plain() {
        return service.plain();
    }

    @GET
    @Path("/boom")
    public String boom() {
        return service.alwaysFails(); // throws → 500 → status ERROR on span SERVER
    }
}
