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
package io.vidocq.runtime.examples.mansart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Writes and reads {@link AuditEntry} rows through {@link AuditEntryRepository}, whose
 * {@code @Repository(dataStore = "audit")} routes to the <b>named</b> {@code "audit"} datasource —
 * a different H2 database than {@link ProductResource} uses. Demonstrates the second half of the
 * multi-datasource feature (the first being {@code @Inject @Named("audit") DataSource} in
 * {@link SchemaInitializer}).
 *
 * <pre>
 * curl -X POST -H 'content-type: application/json' \
 *      -d '{"action":"manual audit"}' http://localhost:8080/api/audit
 * curl http://localhost:8080/api/audit        # rows from the "audit" DB
 * curl http://localhost:8080/api/audit/count  # → count in the "audit" DB
 * </pre>
 */
@ApplicationScoped
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditEntryRepository auditLog;

    @GET
    public List<AuditEntry> list() {
        return auditLog.findAll().toList();
    }

    @POST
    @Transactional
    public Response record(AuditEntry input) {
        AuditEntry saved = auditLog.save(new AuditEntry(input.getAction()));
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @GET
    @Path("/count")
    @Produces(MediaType.TEXT_PLAIN)
    public long count() {
        return auditLog.count();
    }
}
