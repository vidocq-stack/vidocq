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
    // io.vidocq.vauban.core : the resource ships as a Vauban-discovered bean (vauban-beans.list)
    // and Vauban instantiates it reflectively. Qualified open — inert/Weld-safe when vauban.core
    // is absent (e.g. plain Weld in the TCK). Mirrors knock-jaxrs (ARAGO-003).
    opens io.vidocq.dirac.rest to jakarta.cdi, jakarta.ws.rs, io.vidocq.vauban.core;
}

