package acme.placed;

import acme.gadget.Gadget;
import acme.gadget.Gadgets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;

/** Prints what the client proxy of the third-party class answers, and where its class lives. */
@ApplicationScoped
public class PlacedProbe {

    @Inject
    Gadget gadget;

    void onStart(@Observes Startup startup) {
        System.out.println("GADGET-NAME " + gadget.name());
        // The library calls its package-private member on the proxy: only a proxy of the library's package
        // overrides it, and so answers with the name of the produced instance.
        System.out.println("GADGET-LABEL " + Gadgets.labelOf(gadget, "x"));
        // One gadget was produced. A second run of a constructor is the client proxy's own.
        System.out.println("GADGET-CONSTRUCTED " + Gadget.constructed());
        Class<?> proxy = gadget.getClass();
        System.out.println("GADGET-PROXY " + proxy.getName() + " in " + proxy.getModule().getName());
    }
}
