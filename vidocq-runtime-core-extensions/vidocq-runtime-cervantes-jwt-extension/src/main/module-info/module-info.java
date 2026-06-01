/**
 * Vidocq Runtime wrapper that activates Cervantes (MicroProfile JWT 2.1)
 * via its standard SPIs (BCE CDI 4.1 + @Provider JAX-RS beans).
 *
 * <p>Transitively re-exports Cervantes modules (api, core, cdi.vauban,
 * cassini) so that they are visible on the module-path of a jlink image
 * Vidocq, and republishes the {@code BuildCompatibleExtension} from cervantes-cdi-vauban
 * under the name of this module so that ServiceLoader discovers it.</p>
 */
module io.vidocq.runtime.ext.cervantes.jwt {
    requires transitive io.vidocq.cervantes.api;
    requires transitive io.vidocq.cervantes.core;
    requires transitive io.vidocq.cervantes.cdi.vauban;
    requires transitive io.vidocq.cervantes.jaxrs;

    // MP JWT 2.1 §6.1 — the spec requires reading mp.jwt.verify.* keys via
    // MicroProfile Config. Cervantes-core uses @ConfigProperty for this, so
    // we transitively draw the Ravel extension (which brings ravel-cdi-vauban +
    // the RavelConfigSourceProvider).
    requires transitive io.vidocq.runtime.ext.ravel;

    requires jakarta.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension;
}
