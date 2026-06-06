/**
 * Sécurité JAX-RS de Cervantes pour MicroProfile JWT 2.1.
 *
 * <p>API JAX-RS standard uniquement (filtres, {@code DynamicFeature}, {@code SecurityContext}) :
 * le filtre d'authentification valide le bearer token et pose un {@code SecurityContext} adossé au
 * {@code JsonWebToken} (via {@code setSecurityContext}, pre-matching), et une {@code DynamicFeature}
 * applique {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll} par méthode. Impl-agnostique
 * (validé contre Cassini). Les filtres sont des beans CDI {@code @Provider} découverts par le
 * {@code BeanProvider} de Cassini.</p>
 *
 * <p>Note JPMS — workaround testCompile : {@code module-info.java} dans {@code src/main/module-info/}.
 * Voir {@code cervantes-core/pom.xml}.</p>
 */
module io.vidocq.cervantes.jaxrs {
    requires transitive io.vidocq.cervantes.api;
    requires io.vidocq.cervantes.cdi.vauban;
    requires jakarta.ws.rs;
    requires jakarta.annotation; // @RolesAllowed/@PermitAll/@DenyAll + @Priority lus à l'exécution

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static org.eclipse.microprofile.config; // mp.jwt.token.header / mp.jwt.token.cookie
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    // In-module instantiation of the no-arg @Provider beans (generated as _VaubanComponents),
    // so the container needs no reflection for them.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.cervantes.jaxrs._VaubanComponents;

    exports io.vidocq.cervantes.jaxrs;
    // No `opens … to io.vidocq.vauban.core`: the @Provider beans (auth filter, @RolesAllowed
    // DynamicFeature) are instantiated in-module by the generated _VaubanComponents provider
    // (declared above), so the container needs no deep reflection into this package.
}
