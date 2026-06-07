/**
 * Module REST optionnel de Dirac exposant l'endpoint MicroProfile Metrics §2.3.
 */
module io.vidocq.dirac.rest {
    requires io.vidocq.dirac.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.ws.rs;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    exports io.vidocq.dirac.rest;

    // In-module instantiation and field injection of MetricsResource (its package-private @Inject
    // MetricRegistry fields are assigned by an in-package putfield) through the generated
    // _VaubanComponents — so Vauban needs no `opens … to io.vidocq.vauban.core`.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.dirac.rest._VaubanComponents;

    // Still opened to the CDI/JAX-RS runtime for its own introspection (not to Vauban).
    opens io.vidocq.dirac.rest to jakarta.cdi, jakarta.ws.rs;
}

