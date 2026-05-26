package io.vidocq.runtime.ext.cervantes;

import io.vidocq.cervantes.cdi.CervantesClaimExtension;

/**
 * Relais BCE local au module wrapper pour republier l'extension CDI Cervantes
 * (MicroProfile JWT 2.1) via ServiceLoader et provides JPMS, de la meme facon
 * que {@code vidocq-runtime-cyrano-extension} republie la BCE Cyrano.
 *
 * <p>La BCE {@link CervantesClaimExtension} (cervantes-cdi-vauban) ajoute le
 * producteur {@code @RequestScoped JsonWebToken} et l'injection {@code @Claim}.
 * Les beans de securite JAX-RS (filtre d'authentification + DynamicFeature
 * {@code @RolesAllowed}) de cervantes-cassini sont des {@code @Provider}
 * decouverts par le {@code VaubanBeanProvider} de Cassini une fois cervantes-cassini
 * present sur le classpath du deploiement.</p>
 */
public final class CervantesJwtBuildCompatibleExtension extends CervantesClaimExtension {
}
