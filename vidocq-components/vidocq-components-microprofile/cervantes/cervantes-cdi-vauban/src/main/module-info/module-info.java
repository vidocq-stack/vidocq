/**
 * Permanent integration of Cervantes for the Vauban container (MicroProfile JWT 2.1 §CDI).
 *
 * <p>Provides the main {@code @RequestScoped JsonWebToken} of the current query (powered)
 * by the {@link io.vidocq.cervantes.cdi.JsonWebTokenContext} context, placed by the filter
 * and produces a {@code JwtValidator} configured from the
 * properties MicroProfile Config {@code mp.jwt.verify.*} (via Ravel).</p>
 *
 * <p>Note JPMS — workaround testCompile: {@code module-info.java} in {@code src/main/module-info/}
 * (vauban-core/level-core are test-scope, absent from the path module). See ZZPH0ZZ.ZZPH1ZZ
 */
module io.vidocq.cervantes.cdi.vauban {
    requires transitive io.vidocq.cervantes.core;
    requires org.eclipse.microprofile.config;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cervantes.cdi.CervantesClaimExtension;

    exports io.vidocq.cervantes.cdi;
    exports io.vidocq.cervantes.cdi.internal;
}
