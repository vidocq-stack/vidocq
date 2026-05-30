/**
 * Humboldt Context — provides the OpenTelemetry {@code ContextStorageProvider}
 * implementation for the Humboldt runtime.
 *
 * <p>Discovered by OTel via {@link java.util.ServiceLoader} (JPMS
 * {@code provides} binding + {@code META-INF/services} fallback for
 * classpath environments).</p>
 *
 * <p>The implementation relies on a {@link ThreadLocal}; since JDK 21,
 * {@code ThreadLocal} no longer causes carrier-thread pinning for virtual
 * threads in pure Java code (see JEP 444). The ADR for a possible migration
 * to {@code ScopedValue} (JEP 506) is postponed
 * to M8 (see PLAN.md §7 and §15.1).</p>
 */
module io.vidocq.humboldt.context {

    requires transitive io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.context;

    provides io.opentelemetry.context.ContextStorageProvider
            with io.vidocq.humboldt.context.HumboldtContextStorageProvider;
}
