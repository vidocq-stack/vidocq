package io.vidocq.runtime.examples.extlib;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource in an external library that was not pre-processed.
 * No explicit CDI scope: the RestScopeExtension BCE (or the
 * VidocqGenerateMojo) must add {@code @RequestScoped} at build time
 * or at runtime in the consuming application.
 */
@Path("/external")
public class ExternalResource {

    @Inject
    ExternalService service;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "External: " + service.compute();
    }
}
