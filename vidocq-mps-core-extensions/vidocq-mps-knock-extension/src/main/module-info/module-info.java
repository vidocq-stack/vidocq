/**
 * <h2>Vidocq-MPS :: Knock health extension</h2>
 *
 * <p>Module <em>wrapper</em> qui active Knock (MicroProfile Health 4.0) dans un
 * déploiement vidocq-mps. Aucune classe Java propre — voir
 * {@code docs/adr/ADR-002-vidocq-mps-integration-strategy.md} dans le repo
 * {@code knock}.</p>
 *
 * <p>L'intégration repose à 100 % sur des SPI standards :</p>
 * <ul>
 *   <li>CDI 4.1 BuildCompatibleExtension de {@code io.vidocq.knock.cdi.vauban}
 *       — découvre les beans {@code @Liveness/@Readiness/@Startup} ;</li>
 *   <li>JAX-RS scanning de {@code @Path} beans par
 *       {@code vidocq-mps-rest-cassini-extension} — mount automatique de
 *       {@code KnockHealthResource} (@Path("/health")).</li>
 * </ul>
 *
 * <p>Endpoints exposés (préfixés par {@code vidocq.rest.context-path}) :
 * {@code /health}, {@code /health/live}, {@code /health/ready},
 * {@code /health/started}.</p>
 */
module io.vidocq.mpserver.ext.knock {
    // Modules Knock — re-exposés transitivement aux applications consommatrices
    // pour que les beans @Liveness/@Readiness/@Startup utilisateur puissent
    // implémenter HealthCheck sans déclarer manuellement les requires Knock.
    requires transitive io.vidocq.knock.api;
    requires transitive io.vidocq.knock.core;
    requires transitive io.vidocq.knock.cdi.vauban;
    requires transitive io.vidocq.knock.cassini;

    // Extension REST Cassini : c'est elle qui scanne KnockHealthResource (@Path).
    requires io.vidocq.mpserver.ext.rest.cassini;

    requires jakarta.cdi;
    requires jakarta.ws.rs;
}

