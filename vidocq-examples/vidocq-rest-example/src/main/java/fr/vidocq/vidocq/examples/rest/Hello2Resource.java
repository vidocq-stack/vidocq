package fr.vidocq.vidocq.examples.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Ressource JAX-RS d'exemple.
 */
@Path("/hello2")
@RequestScoped
public class Hello2Resource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from Vidocq! ";
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public String helloJson() {
        return """
                {"message": "Hello from Vidocq!"}""";
    }
}
