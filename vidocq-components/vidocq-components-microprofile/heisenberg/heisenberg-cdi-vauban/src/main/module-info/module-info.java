/**
 * Heisenberg CDI integration for the Vauban container.
 *
 * <p>Planned components (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code FaultToleranceInterceptor} — CDI {@code @Interceptor} with priority 4010
 *       (configurable via {@code mp.fault.tolerance.interceptor.priority}).</li>
 *   <li>{@code HeisenbergExtension} — Vauban BCE: validates FT configurations at container startup
 *       (fallback methods, async return types).</li>
 *   <li>{@code StateRegistryBean} — {@code @ApplicationScoped} bean carrying the global state
 *       of CircuitBreaker and Bulkhead (identified by {@code beanClass + method}).</li>
 * </ul>
 *
 * <p><strong>JPMS note — testCompile workaround</strong>:
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven
 * Compiler Plugin from detecting JPMS during {@code testCompile} (vauban-core and ravel-core
 * are test-scope, absent from {@code target/javamodules/}).
 * See {@code heisenberg-core/pom.xml} for the complete description of the workaround.</p>
 */
module io.vidocq.heisenberg.cdi.vauban {
    requires transitive io.vidocq.heisenberg.core;
    requires org.eclipse.microprofile.config;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    requires static jakarta.interceptor;
    requires static microprofile.metrics.api;
    requires static io.opentelemetry.api;

    exports io.vidocq.heisenberg.cdi.internal;

    // Heisenberg BCE: validate FT configurations at container startup
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;

    provides org.eclipse.microprofile.config.spi.ConfigProviderResolver
            with io.vidocq.heisenberg.cdi.internal.HeisenbergAutoDiscovery;
}
