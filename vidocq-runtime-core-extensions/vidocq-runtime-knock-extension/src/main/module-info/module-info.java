/**
 * <h2>Vidocq Runtime :: Knock health extension</h2>
 *
 * <p><em>wrapper</em> module that activates Knock (MicroProfile Health 4.0) in a
 * vidocq deployment. No own Java class — see
 * {@code docs/adr/ADR-002-vidocq-runtime-integration-strategy.md} in the repo
 * {@code knock}.</p>
 *
 * <p>The integration is 100% based on standard SPIs:</p>
 * <ul>
 *   <li>CDI 4.1 BuildCompatibleExtension of {@code io.vidocq.knock.cdi.vauban}
 *       — discover {@code @Liveness/@Readiness/@Startup} beans;</li>
 *   <li>JAX-RS scanning {@code @Path} beans by
 *       {@code vidocq-runtime-cassini-rest-extension} — automatic mount of
 *       {@code KnockHealthResource} (@Path("/health")).</li>
 * </ul>
 *
 * <p>Exposed endpoints (prefixed by {@code vidocq.rest.context-path}):
 * {@code /health}, {@code /health/live}, {@code /health/ready},
 * {@code /health/started}.</p>
 */
module io.vidocq.runtime.ext.knock {
    // Knock modules — transitively re-exposed to consuming applications
    // so that user @Liveness/@Readiness/@Startup beans can
    // implement HealthCheck without manually declaring requires Knock.
    requires transitive io.vidocq.knock.api;
    requires transitive io.vidocq.knock.core;
    requires transitive io.vidocq.knock.cdi.vauban;
    requires transitive io.vidocq.knock.cassini;

    // Cassini REST extension: it scans KnockHealthResource (@Path).
    requires io.vidocq.runtime.ext.rest.cassini;

    requires jakarta.cdi;
    requires jakarta.ws.rs;
}

