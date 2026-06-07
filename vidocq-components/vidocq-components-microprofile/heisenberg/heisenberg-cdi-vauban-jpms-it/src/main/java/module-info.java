/**
 * Module-path proof vehicle for Heisenberg CDI: verifies that a {@code @Retry} bean is intercepted on
 * the strict module path with NO {@code opens} directive — including the {@code FaultToleranceInterceptor}
 * itself, which is instantiated in-module through the generated provider (VAU-INT-004) and whose
 * {@code @Inject} fields are package-private (assigned by an in-package {@code putfield}).
 *
 * <p>The Vauban APT generates {@code FaultToleranceService$$Intercepted} (build time) plus the in-module
 * {@code _VaubanComponents} provider declared below; the Vauban container instantiates, field-injects and
 * runs the interception chain through that provider, so this module opens nothing to
 * {@code io.vidocq.vauban.core}. It depends on {@code vauban-core} for real (it boots a container).</p>
 */
module io.vidocq.heisenberg.cdi.jpmsit {
    requires io.vidocq.heisenberg.cdi.vauban;
    requires io.vidocq.vauban.core;
    requires microprofile.fault.tolerance.api;

    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.heisenberg.cdi.jpmsit;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.heisenberg.cdi.jpmsit._VaubanComponents;
}
