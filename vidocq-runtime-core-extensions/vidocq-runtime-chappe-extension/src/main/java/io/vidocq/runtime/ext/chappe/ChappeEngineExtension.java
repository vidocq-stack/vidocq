package io.vidocq.runtime.ext.chappe;

import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;

/**
 * Extension Vidocq qui installe le {@link ChappeMountPoint} partagé.
 * <p>
 * Priorité faible (100) : tourne avant les extensions contributrices (REST, Servlet, ...)
 * pour que celles-ci puissent appeler {@link ChappeMountPoint#instance()} dans leur
 * phase {@code onStart}.
 * </p>
 * <p>Le démarrage effectif des serveurs Chappe est assuré par
 * {@link ChappeServerBootstrap} en fin de chaîne (priorité 10 000).</p>
 */
public final class ChappeEngineExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(ChappeEngineExtension.class.getName());

    @Override
    public String name() {
        return "chappe-engine";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        ChappeMountPoint mp = new ChappeMountPoint();
        ChappeMountPoint.install(mp);
        LOG.log(System.Logger.Level.INFO, "Chappe engine: mount point ready");
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        // Rien : l'instance est exposée statiquement via ChappeMountPoint.instance().
        // Une intégration CDI par @Produces pourra être ajoutée sans changer l'API.
    }
}
