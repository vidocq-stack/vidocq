/**
 * Humboldt Context — fournit l'implémentation {@code ContextStorageProvider}
 * d'OpenTelemetry pour le runtime Humboldt.
 *
 * <p>Découvert par OTel via {@link java.util.ServiceLoader} (binding par
 * {@code provides} JPMS + fallback {@code META-INF/services} pour les
 * environnements classpath).</p>
 *
 * <p>L'implémentation s'appuie sur un {@link ThreadLocal} ; depuis JDK 21,
 * les {@code ThreadLocal} ne provoquent plus de pinning de carrier thread
 * pour les virtual threads sur le code Java pur (cf. JEP 444). L'ADR sur
 * une éventuelle migration vers {@code ScopedValue} (JEP 506) est reportée
 * en M8 (cf. PLAN.md §7 et §15.1).</p>
 */
module io.vidocq.humboldt.context {

    requires transitive io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.context;

    provides io.opentelemetry.context.ContextStorageProvider
            with io.vidocq.humboldt.context.HumboldtContextStorageProvider;
}
