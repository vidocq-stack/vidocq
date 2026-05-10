/**
 * Endpoints Jakarta REST de Knock via Cassini — ressources JAX-RS {@code @Path("/health*")}
 * délégant au {@link io.vidocq.knock.spi.HealthCheckRegistry}. Utilise uniquement l'API
 * {@code jakarta.ws.rs} standard : aucun import de classe interne Cassini.
 *
 * <p>Module optionnel : un déploiement sans Jakarta REST peut interroger le registry
 * directement (CLI, tests, autres transports).</p>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong>
 * (voir {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :
 * {@code module-info.java} est dans {@code src/main/module-info/} pour éviter que
 * Maven Compiler Plugin détecte JPMS lors de {@code testCompile} (cassini-core est
 * test-scope, absent de {@code target/javamodules/}).</p>
 */
module io.vidocq.knock.cassini {
    requires transitive io.vidocq.knock.core;

    // Jakarta REST API (implémentation fournie par Cassini à l'exécution)
    requires jakarta.ws.rs;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    // Ressource JAX-RS exposée — découvrable par Cassini via scanning JAX-RS
    exports io.vidocq.knock.cassini;
}

