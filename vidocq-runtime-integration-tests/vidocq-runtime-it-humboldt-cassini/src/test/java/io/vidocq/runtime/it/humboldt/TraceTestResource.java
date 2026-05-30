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
