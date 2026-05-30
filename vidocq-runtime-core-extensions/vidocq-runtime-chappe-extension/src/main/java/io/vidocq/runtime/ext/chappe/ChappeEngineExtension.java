package io.vidocq.runtime.ext.chappe;

import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;

/**
 * Vidocq extension which installs the shared {@link ChappeMountPoint}.
 * <p>
 * Low priority (100): runs before contributing extensions (REST, Servlet, etc.)
 * so that they can call {@link ChappeMountPoint#instance()} in their
 * phase {@code onStart}.
 * </p>
 * <p>The effective startup of the Chappe servers is ensured by
 * {@link ChappeServerBootstrap} at the end of the chain (priority 10,000).</p>
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
        // Nothing: the instance is statically exposed via ChappeMountPoint.instance().
        // A CDI integration by @Produces can be added without changing the API.
    }
}
