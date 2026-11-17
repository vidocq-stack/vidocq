package acme.reload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A resource that reads the configuration and writes JSON: what each layer of a hot reload loads, and what the
 * runtime must let go with it. AppLayerReloadLeakIT adds a method below for each reload.
 */
@ApplicationScoped
@Path("/greeting")
public class GreetingResource {

    @Inject
    @ConfigProperty(name = "greeting.salutation")
    String salutation;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Greeting greet(@QueryParam("name") String name) {
        return new Greeting(salutation, name);
    }
}
