/**
 * Pure Java 25 implementations of the MicroProfile Metrics 5.1.1 metrics — no CDI dependency.
 *
 * <p>Planned components (see ROADMAP.md M1-M6) :</p>
 * <ul>
 *   <li>{@code CounterImpl} — incremental counter via {@code LongAdder}.</li>
 *   <li>{@code GaugeImpl} — instant value via {@code MethodHandle} resolved at startup.</li>
 *   <li>{@code HistogramImpl} — EWMA reservoir with percentiles, thread-safe via {@code AtomicLongArray}.</li>
 *   <li>{@code TimerImpl} — call duration via {@code System.nanoTime()} + {@code HistogramImpl}.</li>
 *   <li>{@code MetricRegistryImpl} — thread-safe registry by scope ({@code ConcurrentHashMap}).</li>
 *   <li>{@code OpenMetricsFormatter} — Prometheus text 0.0.4 format serialization.</li>
 *   <li>{@code JsonMetricsFormatter} — JSON serialization in MP Metrics spec §3.2 format.</li>
 *   <li>{@code BaseMetricsRegistrar} — mandatory JVM metrics (GC, threads, heap, uptime).</li>
 * </ul>
 *
 * <p><strong>JPMS note — testCompile workaround</strong> :
 * This {@code module-info.java} is in {@code src/main/module-info/} (not
 * {@code src/main/java/}) so that Maven Compiler Plugin does not detect JPMS during
 * {@code testCompile}. {@code maven-clean-plugin} deletes {@code module-info.class} before
 * {@code testCompile} (incremental builds). A {@code prepare-package} execution
 * recompiles {@code module-info.java} alone. Tests run on the classpath
 * ({@code useModulePath=false}) — JPMS wiring is validated by the smoke TCK.</p>
 */
module io.vidocq.dirac.core {
    requires transitive io.vidocq.dirac.api;

    exports io.vidocq.dirac.internal to io.vidocq.dirac.cdi.vauban, io.vidocq.dirac.rest;
}
