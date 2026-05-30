module io.vidocq.runtime.core {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires java.management;

    exports io.vidocq.runtime.core;
    exports io.vidocq.runtime.core.config;

    uses io.vidocq.runtime.spi.VidocqExtension;
    uses io.vidocq.runtime.spi.config.ConfigSource;
    // When a ConfigSourceProvider is registered (e.g. via
    // vidocq-runtime-ravel-extension), it replaces the native sources below.
    uses io.vidocq.runtime.spi.config.ConfigSourceProvider;

    provides io.vidocq.runtime.spi.config.ConfigSource with
            io.vidocq.runtime.core.config.SystemPropertiesConfigSource,
            io.vidocq.runtime.core.config.EnvConfigSource,
            io.vidocq.runtime.core.config.ExternalFileConfigSource,
            io.vidocq.runtime.core.config.PropertiesFileConfigSource;
}
