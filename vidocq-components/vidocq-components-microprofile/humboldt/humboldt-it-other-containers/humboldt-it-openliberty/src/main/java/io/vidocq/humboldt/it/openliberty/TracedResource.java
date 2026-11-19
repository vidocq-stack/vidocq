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
package io.vidocq.humboldt.it.openliberty;

import io.vidocq.humboldt.sdk.trace.data.SpanData;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

/** Endpoints the test calls over HTTP, and a view of the spans Humboldt recorded. */
@Path("/traced")
@Produces(MediaType.TEXT_PLAIN)
public class TracedResource {

    @GET
    @Path("/hello")
    public String hello() {
        return "hello";
    }

    /** Echoes the W3C {@code traceparent} header this request carried. */
    @GET
    @Path("/traceparent")
    public String traceparent(@HeaderParam("traceparent") String traceparent) {
        return String.valueOf(traceparent);
    }

    @GET
    @Path("/fail")
    public String fail() {
        throw new IllegalStateException("boom");
    }

    /** Calls {@link #traceparent} through a MicroProfile Rest Client and returns what it received. */
    @GET
    @Path("/call")
    public String call(@Context UriInfo uri) {
        return RestClientBuilder.newBuilder().baseUri(uri.getBaseUri()).build(EchoClient.class).traceparent();
    }

    /** One line per finished span: {@code kind|name|traceId|status|event names}. */
    @GET
    @Path("/spans")
    public String spans() {
        return TelemetryBootstrap.humboldt.inMemorySpanExporter().getFinishedSpans().stream()
                .map(TracedResource::line)
                .collect(Collectors.joining("\n"));
    }

    private static String line(SpanData span) {
        return span.kind() + "|" + span.name() + "|" + span.spanContext().getTraceId() + "|"
                + span.status().code() + "|" + span.events().stream().map(e -> e.name()).toList();
    }
}
