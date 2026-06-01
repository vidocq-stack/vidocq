package io.vidocq.runtime.ext.cervantes;

import io.vidocq.cervantes.cdi.CervantesClaimExtension;

/**
 * BCE relay local to wrapper module to republish CDI Cervantes extension
 * (MicroProfile JWT 2.1) via ServiceLoader and provides JPMS, in the same way
 * that {@code vidocq-runtime-cyrano-extension} republishes ECB Cyrano.
 *
 * <p>The ECB {@link CervantesClaimExtension} (cervantes-cdi-vauban) adds the
 * producer {@code @RequestScoped JsonWebToken} and injection {@code @Claim}.
 * JAX-RS security beans (authentication filter + DynamicFeature
 * {@code @RolesAllowed}) of cervantes-jaxrs are {@code @Provider}
 * discovered by Cassini's {@code VaubanBeanProvider} once Cervantes-Cassini
 * present on the deployment classpath.</p>
 */
public final class CervantesJwtBuildCompatibleExtension extends CervantesClaimExtension {
}
