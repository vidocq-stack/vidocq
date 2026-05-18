/**
 * Moteurs de politiques Fault Tolerance purs Java 25 — aucune dépendance CDI.
 *
 * <p>Composants prévus (cf. ROADMAP.md M1-M7) :</p>
 * <ul>
 *   <li>{@code RetryEngine} — automate de retry avec délai, jitter, maxDuration.</li>
     *   <li>{@code TimeoutEngine} — timeout via {@code Thread.ofVirtual() + join(Duration)} (Java 21+, finalisé).</li>
 *   <li>{@code CircuitBreakerEngine} — disjoncteur CLOSED/OPEN/HALF_OPEN, fenêtre glissante.</li>
 *   <li>{@code BulkheadEngine} — isolation par {@code Semaphore} (sync) ou file (async).</li>
 *   <li>{@code FallbackResolver} — résolution {@code FallbackHandler} ou {@code fallbackMethod}
 *       via {@code MethodHandle}.</li>
 *   <li>{@code PolicyComposer} — chaîne de politiques dans l'ordre spec §2.5.</li>
 *   <li>{@code AnnotationReader} — lecture des annotations FT sur méthode puis classe.</li>
 *   <li>{@code ConfigResolver} — précédence MP Config §9 (méthode > classe > global).</li>
 * </ul>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong> :
 * Ce {@code module-info.java} est dans {@code src/main/module-info/} (pas
 * {@code src/main/java/}) pour que Maven Compiler Plugin ne détecte pas JPMS lors de
 * {@code testCompile}. {@code maven-clean-plugin} supprime {@code module-info.class} avant
 * {@code testCompile} (builds incrémentaux). Une exécution {@code prepare-package}
 * recompile {@code module-info.java} seul. Les tests s'exécutent sur le classpath
 * ({@code useModulePath=false}) — le câblage JPMS est validé par le smoke TCK.</p>
 */
module io.vidocq.heisenberg.core {
    requires transitive io.vidocq.heisenberg.api;

    // Configuration externe via MicroProfile Config (Ravel en runtime)
    requires org.eclipse.microprofile.config;

    exports io.vidocq.heisenberg.internal to io.vidocq.heisenberg.cdi.vauban;
}
