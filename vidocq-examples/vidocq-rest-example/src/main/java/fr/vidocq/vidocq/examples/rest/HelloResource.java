package fr.vidocq.vidocq.examples.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Ressource JAX-RS d'exemple.
 * <p>
 * Pas de scope CDI explicite : la {@code RestScopeExtension} (BCE)
 * ajoute automatiquement {@code @RequestScoped}.
 * </p>
 */
@Path("/hello")
public class HelloResource {
    private final Generator generator;

    // Requis par Vauban pour le proxying @RequestScoped (cf. VAUBAN-BUGS.md #1)
    protected HelloResource() {
        this.generator = null;
    }

    @Inject
    public HelloResource(Generator generator) {
        this.generator = generator;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from Vidocq! "+generator.generate();
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public String helloJson() {
        return """
                {"message": "Hello from Vidocq!"}""";
    }
}
