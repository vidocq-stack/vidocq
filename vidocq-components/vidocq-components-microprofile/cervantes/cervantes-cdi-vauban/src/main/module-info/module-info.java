/**
 * Intégration CDI de Cervantes pour le container Vauban (MicroProfile JWT 2.1 §CDI).
 *
 * <p>Fournit le principal {@code @RequestScoped JsonWebToken} de la requête courante (alimenté
 * par le contexte {@link io.vidocq.cervantes.cdi.JsonWebTokenContext}, posé par le filtre
 * d'authentification JAX-RS en M4) et produit un {@code JwtValidator} configuré à partir des
 * propriétés MicroProfile Config {@code mp.jwt.verify.*} (via Ravel).</p>
 *
 * <p>Note JPMS — workaround testCompile : {@code module-info.java} dans {@code src/main/module-info/}
 * (vauban-core/ravel-core sont test-scope, absents du module-path). Voir {@code cervantes-core/pom.xml}.</p>
 */
module io.vidocq.cervantes.cdi.vauban {
    requires transitive io.vidocq.cervantes.core;
    requires org.eclipse.microprofile.config;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    exports io.vidocq.cervantes.cdi;
    exports io.vidocq.cervantes.cdi.internal;
}
