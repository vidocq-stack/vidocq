/**
 * Intégration CDI de Heisenberg pour le container Vauban.
 *
 * <p>Composants prévus (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code FaultToleranceInterceptor} — intercepteur CDI {@code @Interceptor} de priorité 4010
 *       (configurable via {@code mp.fault.tolerance.interceptor.priority}).</li>
 *   <li>{@code HeisenbergExtension} — BCE Vauban : valide les configurations FT au démarrage
 *       du container (méthodes fallback, types de retour async).</li>
 *   <li>{@code StateRegistryBean} — bean {@code @ApplicationScoped} portant l'état global
 *       des CircuitBreaker et Bulkhead (identifiés par {@code beanClass + method}).</li>
 * </ul>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong> :
 * {@code module-info.java} est dans {@code src/main/module-info/} pour éviter que Maven
 * Compiler Plugin détecte JPMS lors de {@code testCompile} (vauban-core et ravel-core
 * sont test-scope, absents de {@code target/javamodules/}).
 * Voir {@code heisenberg-core/pom.xml} pour la description complète du workaround.</p>
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

    // BCE Heisenberg : validation des configurations FT au démarrage du container
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;

    provides org.eclipse.microprofile.config.spi.ConfigProviderResolver
            with io.vidocq.heisenberg.cdi.internal.HeisenbergAutoDiscovery;
}
