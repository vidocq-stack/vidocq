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

    exports io.vidocq.humboldt.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension;
}
