/**
 * Implémentation MicroProfile Health 4.0 standalone — utilisable en SE pur,
 * sans CDI ni container.
 *
 * <p>La sérialisation JSON est produite via Jakarta JSON-P (jakarta.json) ;
 * champollion en est l'implémentation de référence fournie à l'exécution.</p>
 *
 * <p><strong>Note JPMS — deux conséquences du module automatique
 * {@code microprofile.health.api}</strong>
 * (voir {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :</p>
 *
 * <ol>
 *   <li><em>Déclaration {@code uses} impossible.</em> {@code HealthCheckResponse.named()}
 *   invoque {@code ServiceLoader} via {@code Thread.currentThread().getContextClassLoader()}.
 *   {@code KnockHealthCheckResponseProvider} est donc enregistré <em>deux fois</em> :
 *   {@code provides ... with} ici (ServiceLoader JPMS) et dans
 *   {@code META-INF/services/} (ServiceLoader ClassLoader — obligatoire car c'est ce
 *   mécanisme qu'utilise {@code HealthCheckResponse}).</li>
 *
 *   <li><em>Build Maven en trois temps.</em> {@code module-info.java} est placé dans
 *   {@code src/main/module-info/} (pas {@code src/main/java/}) pour que Maven Compiler
 *   Plugin ne détecte pas JPMS lors de {@code testCompile}. {@code maven-clean-plugin}
 *   purge {@code module-info.class} avant {@code testCompile} (builds incrémentiaux).
 *   Une exécution {@code prepare-package} recompile {@code module-info.java} seul avant
 *   l'assemblage du JAR. Les tests s'exécutent sur le classpath
 *   ({@code useModulePath=false}) — le câblage JPMS est validé par le smoke TCK.</li>
 * </ol>
 *
 * <p>Ce contournement est <em>compatible {@code jlink}</em> : placer le JAR
 * {@code microprofile-health-api-*.jar} sur le {@code --module-path} suffit pour que jlink
 * le résolve sous le nom {@code microprofile.health.api}, identique à celui des
 * {@code requires} ci-dessous.</p>
 */
module io.vidocq.knock.core {
    requires transitive io.vidocq.knock.api;

    // JSON-P pour la sérialisation de la réponse health (§3.1 spec)
    requires jakarta.json;

    // SPI runtime exportée — point d'entrée stable pour les adaptateurs
    // (knock-cdi-vauban, knock-cassini). Le package io.vidocq.knock.internal
    // reste volontairement non exporté (frontière JPMS du projet).
    exports io.vidocq.knock.runtime;

    // SPI HealthCheckResponseProvider — déclarée pour les environnements JPMS stricts
    // (le provider est aussi déclaré dans META-INF/services pour le fallback ClassLoader)
    provides org.eclipse.microprofile.health.spi.HealthCheckResponseProvider
            with io.vidocq.knock.internal.KnockHealthCheckResponseProvider;
}
