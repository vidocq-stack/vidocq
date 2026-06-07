/**
 * Humboldt CDI — automatic interception of
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * (standard OpenTelemetry public API annotation, aligned with the MicroProfile
 * Telemetry 2.1 TCK).
 *
 * <p>The user only writes {@code @WithSpan}. The
 * {@link io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension} (CDI 4.x
 * BuildCompatibleExtension) automatically adds the internal marker
 * {@link io.vidocq.humboldt.cdi.SpanBinding} at build time, which activates
 * {@link io.vidocq.humboldt.cdi.WithSpanInterceptor}.</p>
 *
 * <p>Compatible with CDI 4.1 Lite (Vauban) and CDI 4.1 Full (Weld) — the
 * BuildCompatibleExtension is the standard CDI 4.x mechanism shared between
 * Lite and Full.</p>
 */
module io.vidocq.humboldt.cdi {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    // OpenTelemetry instrumentation-annotations: automatic module
    // (Automatic-Module-Name with underscore, not dot).
    requires transitive io.opentelemetry.instrumentation_annotations;
    requires transitive jakarta.cdi;
    requires transitive jakarta.interceptor;
    requires java.logging;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    exports io.vidocq.humboldt.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension;

    // In-module instantiation and producer invocation of this package's beans (the @Produces in
    // HumboldtTelemetryProducers and the WithSpanInterceptor), generated as _VaubanComponents
    // co-located in io.vidocq.humboldt.cdi — so the container needs no `opens … to
    // io.vidocq.vauban.core`. APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.humboldt.cdi._VaubanComponents;
}
