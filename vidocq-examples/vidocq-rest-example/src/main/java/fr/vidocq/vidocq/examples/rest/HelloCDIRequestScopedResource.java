package fr.vidocq.vidocq.examples.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Ressource JAX-RS d'exemple.
 */
@Path("/hello-cdi-request-scoped")
@RequestScoped
public class HelloCDIRequestScopedResource {

    @Inject
    DependentCounter dependentCounter;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "HelloCDIRequestScopedResource from Vidocq! "+dependentCounter.getNextValue();
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public String helloJson() {
        return
                """
                {"message": "Hello from Vidocq! "}
                """;
    }
}
