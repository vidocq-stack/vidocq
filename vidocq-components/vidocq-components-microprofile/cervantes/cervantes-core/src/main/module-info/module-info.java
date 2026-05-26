/**
 * Moteur de validation JWT pur (sans CDI ni JAX-RS).
 *
 * <p>Note JPMS — workaround testCompile : ce {@code module-info.java} est dans
 * {@code src/main/module-info/} (pas {@code src/main/java/}) pour que Maven Compiler Plugin ne
 * détecte pas JPMS lors de {@code testCompile} (les dépendances test — champollion-jsonp, junit —
 * tournent sur le classpath, {@code useModulePath=false}). {@code maven-clean-plugin} retire
 * {@code module-info.class} avant {@code testCompile}, puis une exécution {@code prepare-package}
 * recompile {@code module-info.java} seul. Le câblage JPMS est validé par le smoke TCK (M6).</p>
 *
 * <p>{@code io.vidocq.cervantes.internal} sera exporté de façon qualifiée vers
 * {@code io.vidocq.cervantes.cdi.vauban} et {@code io.vidocq.cervantes.cassini} dès M3/M4.</p>
 */
module io.vidocq.cervantes.core {
    requires transitive io.vidocq.cervantes.api;
    requires io.vidocq.champollion.api; // jakarta.json (transitif)
    requires java.net.http;             // fetch JWKS distant (M2)

    // Implémentation interne consommée par les intégrations CDI (M3) et JAX-RS (M4).
    exports io.vidocq.cervantes.internal to io.vidocq.cervantes.cdi.vauban;
}
