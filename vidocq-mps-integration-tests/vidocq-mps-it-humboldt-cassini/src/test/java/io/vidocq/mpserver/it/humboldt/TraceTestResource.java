package io.vidocq.mpserver.it.humboldt;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource JAX-RS de test pour les 4 scénarios humboldt :
 * <ul>
 *   <li>{@code GET /trace/work} — appelle un service annoté {@code @WithSpan}.
 *       Doit produire 2 spans : SERVER (filter humboldt-rest) + INTERNAL "traced.work" (BCE)</li>
 *   <li>{@code GET /trace/plain} — appelle un service non annoté.
 *       Doit produire 1 span SERVER + AUCUN span INTERNAL</li>
 *   <li>{@code GET /trace/boom} — appelle un service qui throw.
 *       Doit produire 2 spans avec status ERROR</li>
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
        return service.alwaysFails(); // throws → 500 → status ERROR sur span SERVER
    }
}
