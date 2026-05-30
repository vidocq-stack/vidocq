/**
 * Knock Jakarta REST endpoints via Cassini — JAX-RS resources {@code @Path("/health*")}
 * delegating to {@link io.vidocq.knock.spi.HealthCheckRegistry}. Uses only the standard
 * {@code jakarta.ws.rs} API: no internal Cassini class imports.
 *
 * <p>Optional module: a deployment without Jakarta REST can query the registry directly
 * (CLI, tests, other transports).</p>
 *
 * <p><strong>JPMS note — testCompile workaround</strong>
 * (see {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven Compiler
 * Plugin from detecting JPMS during {@code testCompile} (cassini-core is test-scope,
 * absent from {@code target/javamodules/}).</p>
 */
module io.vidocq.knock.cassini {
    requires transitive io.vidocq.knock.core;

    // Jakarta REST API (implementation provided by Cassini at runtime)
    requires jakarta.ws.rs;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    // Exposed JAX-RS resource — discoverable by Cassini via JAX-RS scanning
    exports io.vidocq.knock.cassini;
}
