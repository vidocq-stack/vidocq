/**
 * Module-path proof vehicle for Dirac CDI: verifies that a {@code @Counted} bean is intercepted on
 * the module path with NO {@code opens} directive — including the {@code @Counted}/{@code @Timed}
 * interceptor classes themselves, which are instantiated in-module through the generated provider
 * (VAU-INT-004).
 *
 * <p>The Vauban APT generates {@code MetricsService$$Intercepted} (build time) plus the in-module
 * {@code _VaubanComponents} provider declared below; the Vauban container instantiates, field-injects
 * and runs the interception chain through that provider, so this module opens nothing to
 * {@code io.vidocq.vauban.core}. It depends on {@code vauban-core} for real (it boots a container).</p>
 */
module io.vidocq.dirac.cdi.jpmsit {
    requires io.vidocq.dirac.cdi.vauban;
    requires io.vidocq.vauban.core;
    requires microprofile.metrics.api;

    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.dirac.cdi.jpmsit;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.dirac.cdi.jpmsit._VaubanComponents;
}
