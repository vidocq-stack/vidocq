/**
 * Knock Jakarta REST endpoints — JAX-RS resources {@code @Path("/health*")} delegating to
 * {@link io.vidocq.knock.spi.HealthCheckRegistry}. Uses only the standard {@code jakarta.ws.rs}
 * API: <strong>no Cassini (or any JAX-RS implementation) dependency</strong>, so it stays
 * portable across runtimes (Cassini, RESTEasy, Jersey…). A Vidocq deployment weaves the Cassini
 * adapter into a repackaged copy at build time (cassini-maven-plugin) — the published jar remains
 * implementation-agnostic.
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
module io.vidocq.knock.jaxrs {
    requires transitive io.vidocq.knock.core;

    // Jakarta REST API only (implementation provided by the host runtime, e.g. Cassini)
    requires jakarta.ws.rs;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    // Exposed JAX-RS resource — discovered as a CDI bean (Vauban index) and mounted by the runtime
    exports io.vidocq.knock.jaxrs;

    // The resource ships as a Vauban-discovered bean; Vauban instantiates it reflectively, which
    // requires the package opened to vauban.core. Qualified open (no dependency, no import) — inert
    // for non-Vauban CDI containers (Weld) and for non-Cassini JAX-RS runtimes (RESTEasy, Jersey).
    opens io.vidocq.knock.jaxrs to io.vidocq.vauban.core;
}
