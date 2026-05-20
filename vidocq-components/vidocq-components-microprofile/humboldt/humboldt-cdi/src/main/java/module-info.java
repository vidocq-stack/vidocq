/**
 * Humboldt CDI — interception automatique de
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * (annotation API publique OpenTelemetry, alignée TCK MicroProfile Telemetry 2.1).
 *
 * <p>L'utilisateur écrit uniquement {@code @WithSpan}. La
 * {@link io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension} (CDI 4.x
 * BuildCompatibleExtension) ajoute automatiquement le marker interne
 * {@link io.vidocq.humboldt.cdi.SpanBinding} au build time, ce qui active
 * {@link io.vidocq.humboldt.cdi.WithSpanInterceptor}.</p>
 *
 * <p>Compatible CDI 4.1 Lite (Vauban) et CDI 4.1 Full (Weld) — la
 * BuildCompatibleExtension est le mécanisme standard CDI 4.x partagé entre
 * Lite et Full.</p>
 */
module io.vidocq.humboldt.cdi {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    // OpenTelemetry instrumentation-annotations : module automatique
    // (Automatic-Module-Name avec underscore, pas point).
    requires transitive io.opentelemetry.instrumentation_annotations;
    requires transitive jakarta.cdi;
    requires transitive jakarta.interceptor;
    requires java.logging;

    exports io.vidocq.humboldt.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension;
}
