/**
 * Wrapper Vidocq Runtime qui active Cervantes (MicroProfile JWT 2.1)
 * via ses SPI standards (BCE CDI 4.1 + beans @Provider JAX-RS).
 *
 * <p>Re-exporte transitivement les modules Cervantes (api, core, cdi.vauban,
 * cassini) afin qu'ils soient visibles sur le module-path d'une image jlink
 * Vidocq, et republie la {@code BuildCompatibleExtension} de cervantes-cdi-vauban
 * sous le nom de ce module pour que ServiceLoader la decouvre.</p>
 */
module io.vidocq.runtime.ext.cervantes.jwt {
    requires transitive io.vidocq.cervantes.api;
    requires transitive io.vidocq.cervantes.core;
    requires transitive io.vidocq.cervantes.cdi.vauban;
    requires transitive io.vidocq.cervantes.cassini;

    // MP JWT 2.1 §6.1 — la spec impose la lecture des clés mp.jwt.verify.* via
    // MicroProfile Config. Cervantes-core utilise @ConfigProperty pour ça, donc
    // on tire transitivement l'extension Ravel (qui apporte ravel-cdi-vauban +
    // le RavelConfigSourceProvider).
    requires transitive io.vidocq.runtime.ext.ravel;

    requires jakarta.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension;
}
