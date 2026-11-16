package acme.placed;

import acme.gadget.Gadget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** Produces a class of the third-party library in a normal scope, so the container hands out a client proxy of it. */
@ApplicationScoped
public class GadgetProducer {

    @Produces
    @ApplicationScoped
    Gadget gadget() {
        return new Gadget("placed");
    }
}
