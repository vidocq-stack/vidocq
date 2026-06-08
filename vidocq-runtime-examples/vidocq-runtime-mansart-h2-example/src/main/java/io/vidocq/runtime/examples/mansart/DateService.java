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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny stateless test service that returns the current server time. It exists mainly to show a
 * second resource (besides {@code /products}) appearing in the Grimm-generated OpenAPI document at
 * {@code /openapi} and rendered by the Swagger UI at {@code /openapi/ui}.
 */
@ApplicationScoped
@Path("/date")
@Produces(MediaType.APPLICATION_JSON)
public class DateService {

    /** Current server time as a JSON object (epoch millis, ISO-8601 string, zone id). */
    @GET
    public DateResponse now() {
        ZonedDateTime now = ZonedDateTime.now();
        return new DateResponse(
                now.toInstant().toEpochMilli(),
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                now.getZone().getId());
    }

    /** Current server time as a plain ISO-8601 string. */
    @GET
    @Path("/iso")
    @Produces(MediaType.TEXT_PLAIN)
    public String iso() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
