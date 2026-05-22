/**
 * Implémentations pures Java 25 des métriques MicroProfile Metrics 5.1.1 — aucune dépendance CDI.
 *
 * <p>Composants prévus (cf. ROADMAP.md M1-M6) :</p>
 * <ul>
 *   <li>{@code CounterImpl} — compteur incrémental via {@code LongAdder}.</li>
 *   <li>{@code GaugeImpl} — valeur instantanée via {@code MethodHandle} résolu au démarrage.</li>
 *   <li>{@code HistogramImpl} — reservoir EWMA avec percentiles, thread-safe via {@code AtomicLongArray}.</li>
 *   <li>{@code TimerImpl} — durée des appels via {@code System.nanoTime()} + {@code HistogramImpl}.</li>
 *   <li>{@code MetricRegistryImpl} — registre thread-safe par scope ({@code ConcurrentHashMap}).</li>
 *   <li>{@code OpenMetricsFormatter} — sérialisation format Prometheus text 0.0.4.</li>
 *   <li>{@code JsonMetricsFormatter} — sérialisation format JSON spec MP Metrics §3.2.</li>
 *   <li>{@code BaseMetricsRegistrar} — métriques JVM obligatoires (GC, threads, heap, uptime).</li>
 * </ul>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong> :
 * Ce {@code module-info.java} est dans {@code src/main/module-info/} (pas
 * {@code src/main/java/}) pour que Maven Compiler Plugin ne détecte pas JPMS lors de
 * {@code testCompile}. {@code maven-clean-plugin} supprime {@code module-info.class} avant
 * {@code testCompile} (builds incrémentaux). Une exécution {@code prepare-package}
 * recompile {@code module-info.java} seul. Les tests s'exécutent sur le classpath
 * ({@code useModulePath=false}) — le câblage JPMS est validé par le smoke TCK.</p>
 */
module io.vidocq.dirac.core {
    requires transitive io.vidocq.dirac.api;

    exports io.vidocq.dirac.internal to io.vidocq.dirac.cdi.vauban, io.vidocq.dirac.rest;
}
