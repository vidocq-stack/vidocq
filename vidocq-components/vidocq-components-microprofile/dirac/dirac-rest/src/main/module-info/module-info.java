/**
 * Module REST optionnel de Dirac exposant l'endpoint MicroProfile Metrics §2.3.
 */
module io.vidocq.dirac.rest {
    requires io.vidocq.dirac.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.ws.rs;

    exports io.vidocq.dirac.rest;

    // Necessaire pour l'introspection CDI/JAX-RS au runtime.
    opens io.vidocq.dirac.rest to jakarta.cdi, jakarta.ws.rs;
}

