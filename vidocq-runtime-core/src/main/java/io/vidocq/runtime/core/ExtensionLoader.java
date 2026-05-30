package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.VidocqExtension;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers and loads {@link VidocqExtension} via {@link ServiceLoader}.
 * <p>Extensions are sorted by ascending {@link VidocqExtension#priority()}.</p>
 *
 * <p><b>Discovers and loads {@link VidocqExtension}s via {@link ServiceLoader}.</b>
 * Extensions are sorted by ascending {@link VidocqExtension#priority()}.</p>
 */
final class ExtensionLoader {

    private static final System.Logger LOG = System.getLogger(ExtensionLoader.class.getName());

    private ExtensionLoader() {}

    /**
     * Loads all available extensions, sorted by priority.
     */
    static List<VidocqExtension> load() {
        List<VidocqExtension> extensions = ServiceLoader.load(VidocqExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(VidocqExtension::priority))
                .toList();

        if (extensions.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "No Vidocq extensions discovered");
        } else {
            LOG.log(System.Logger.Level.INFO, "Discovered {0} extension(s):", extensions.size());
            for (VidocqExtension ext : extensions) {
                LOG.log(System.Logger.Level.INFO, "  - {0} (priority={1})",
                        ext.name(), ext.priority());
            }
        }

        return extensions;
    }
}
