/**
 * Moteur de validation JWT pur (sans CDI ni JAX-RS).
 *
 * <p>Note JPMS — workaround testCompile: this {@code module-info.java} is in
 * {@code src/main/module-info/} (not {@code src/main/java/}) so Maven Compiler Plugin
 * does not detect JPMS during {@code testCompile} (test dependencies — champollion-jsonp, junit —
 * tournent sur le classpath, {@code useModulePath=false}). {@code maven-clean-plugin} retire
 * {@code module-info.class} before {@code testCompile}, then a {@code prepare-package} run
 * Recompile {@code module-info.java} alone. JPMS wiring is validated by TCK (M6).</p>
 *
 * <p>{@code io.vidocq.cervantes.internal} will be exported qualifiedly to
 * {@code io.vidocq.cervantes.cdi.vauban} and {@code io.vidocq.cervantes.jaxrs} from M3/M4.ZZPH2ZZ
 */
module io.vidocq.cervantes.core {
    requires transitive io.vidocq.cervantes.api;
    requires io.vidocq.champollion.api; // jakarta.json (transitif)
    requires java.net.http;             // fetch JWKS distant (M2)

    //Internal implementation consumed by CDI (M3) and JAX-RS (M4).
    exports io.vidocq.cervantes.internal to io.vidocq.cervantes.cdi.vauban;
}
