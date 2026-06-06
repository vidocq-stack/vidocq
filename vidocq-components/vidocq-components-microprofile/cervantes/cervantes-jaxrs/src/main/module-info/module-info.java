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
    // The @Provider beans (auth filter, @RolesAllowed DynamicFeature) are instantiated by the CDI
    // container via MethodHandles.privateLookupIn; `exports` grants public access but not deep
    // reflection, so open the package to vauban-core. On the class-path (TCK) this is a no-op.
    opens io.vidocq.cervantes.jaxrs to io.vidocq.vauban.core;
}
