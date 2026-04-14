package fr.vidocq.vidocq.it.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/injected")
@RequestScoped
public class InjectedTestResource {

    private final TestService service;

    protected InjectedTestResource() {
        this.service = null;
    }

    @Inject
    public InjectedTestResource(TestService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return service.greet();
    }
}
