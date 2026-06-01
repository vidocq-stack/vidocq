/**
 * <h2>Vidocq Runtime :: Dirac metrics extension</h2>
 *
 * <p><em>wrapper</em> module that activates Dirac (MicroProfile Metrics 5.1) in a vidocq
 * deployment. No own Java class — same strategy as
 * {@code vidocq-runtime-knock-extension} (see ADR-002 in the {@code knock} repo).</p>
 *
 * <p>The integration is 100% based on standard SPIs:</p>
 * <ul>
 *   <li>CDI 4.1 BuildCompatibleExtension of {@code io.vidocq.dirac.cdi.vauban}
 *       — discover {@code @Counted/@Timed/@Gauge} and produce the
 *       {@code @ApplicationScoped MetricRegistry} beans (APPLICATION/BASE/VENDOR);</li>
 *   <li>JAX-RS scanning of {@code @Path} beans by
 *       {@code vidocq-runtime-cassini-rest-extension} — automatic mount of
 *       {@code MetricsResource} (@Path("/metrics")).</li>
 * </ul>
 *
 * <p>Exposed endpoint (prefixed by the configured mount): {@code /metrics} (OpenMetrics
 * text by default, JSON on {@code Accept: application/json}).</p>
 */
module io.vidocq.runtime.ext.dirac {
    // Dirac modules — transitively re-exposed so consuming apps can use @Counted/@Timed/@Gauge
    // and inject MetricRegistry without manually declaring requires on Dirac.
    requires transitive io.vidocq.dirac.api;
    requires transitive io.vidocq.dirac.core;
    requires transitive io.vidocq.dirac.cdi.vauban;
    requires transitive io.vidocq.dirac.rest;

    // Cassini REST extension: it scans the dirac JAX-RS resource (@Path("/metrics")).
    requires io.vidocq.runtime.ext.rest.cassini;

    requires jakarta.cdi;
    requires jakarta.ws.rs;
}
