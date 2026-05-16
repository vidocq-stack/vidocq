/**
 * API Heisenberg : re-exposition contrôlée de la spec MicroProfile Fault Tolerance 4.1
 * et SPI publique stable de l'implémentation Vidocq.
 *
 * <p><strong>Note JPMS — module automatique éventuel sans {@code Automatic-Module-Name}</strong> :
 * Si {@code microprofile-fault-tolerance-api:4.1} n'a ni {@code Automatic-Module-Name} dans son
 * {@code MANIFEST.MF}, ni {@code module-info.class}, le nom JPMS utilisé est
 * {@code microprofile.fault.tolerance.api} (dérivé du nom d'artefact Maven par Java :
 * strip version + remplacement {@code -} par {@code .}).
 * Le POM parent force ce JAR sur le module-path via {@code target/javamodules/}
 * (voir {@code maven-dependency-plugin} en phase {@code initialize}).</p>
 *
 * <p>Contenu prévu (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>Re-export transitif des annotations {@code @Retry}, {@code @Timeout},
 *       {@code @CircuitBreaker}, {@code @Bulkhead}, {@code @Fallback}, {@code @Asynchronous}.</li>
 *   <li>{@code PolicyContext} — contexte d'invocation enrichi exposé aux moteurs du core.</li>
 *   <li>Configs immuables : {@code RetryConfig}, {@code TimeoutConfig},
 *       {@code CircuitBreakerConfig}, {@code BulkheadConfig} (records).</li>
 * </ul>
 */
module io.vidocq.heisenberg.api {
    requires transitive microprofile.fault.tolerance.api;

    exports io.vidocq.heisenberg.api;
}
