package fr.vidocq.vidocq.examples.extlib;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource JAX-RS dans une librairie externe non pre-traitee.
 * Pas de scope CDI explicite : la BCE RestScopeExtension (ou le
 * VidocqGenerateMojo) doit ajouter {@code @RequestScoped} au build
 * ou au runtime de l'application consommatrice.
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
