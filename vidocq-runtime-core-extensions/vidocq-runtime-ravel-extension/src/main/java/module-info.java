import io.vidocq.runtime.ext.ravel.ApplicationPropertiesConfigSource;
import io.vidocq.runtime.ext.ravel.RavelConfigSourceProvider;
import io.vidocq.runtime.ext.ravel.VidocqPropertiesConfigSource;

/**
 * Vidocq Runtime extension that plugs Ravel (MicroProfile Config 3.1) as the
 * config source provider for the runtime, and brings @ConfigProperty CDI
 * injection via the transitive {@code io.vidocq.ravel.cdi.vauban} BCE.
 *
 * <p>When this module is on the module path, {@code vidocq-runtime-core}'s
 * {@code VidocqConfigImpl} picks up {@link RavelConfigSourceProvider} via
 * {@link java.util.ServiceLoader} and routes all config reads through Ravel —
 * including the user's MP custom sources, profiles, and {@code ${}}
 * expressions.</p>
 */
module io.vidocq.runtime.ext.ravel {
    requires io.vidocq.runtime.spi;
    // Transitive so downstream modules can use @Inject @ConfigProperty without
    // re-declaring the Ravel CDI module themselves.
    requires transitive io.vidocq.ravel.cdi.vauban;

    exports io.vidocq.runtime.ext.ravel;

    // Vidocq runtime SPI: discovered by VidocqConfigImpl at boot. When this
    // provider is registered, the native ConfigSource ServiceLoader of
    // vidocq-runtime-core is bypassed entirely — Ravel takes over.
    provides io.vidocq.runtime.spi.config.ConfigSourceProvider
            with RavelConfigSourceProvider;

    // MP Config SPI: backwards-compatibility for the historical Vidocq files
    // (vidocq.properties, application.properties) — Ravel doesn't read them
    // by default, this restores the legacy behavior at the MP ordinal level.
    provides org.eclipse.microprofile.config.spi.ConfigSource
            with VidocqPropertiesConfigSource, ApplicationPropertiesConfigSource;
}
