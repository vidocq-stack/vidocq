module io.vidocq.runtime.core {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires java.management;
    // Ravel MicroProfile Config 3.1 — auto-découverte BCE via ServiceLoader
    requires transitive io.vidocq.ravel.cdi.vauban;

    exports io.vidocq.runtime.core;
    exports io.vidocq.runtime.core.config;

    uses io.vidocq.runtime.spi.VidocqExtension;
    uses io.vidocq.runtime.spi.config.ConfigSource;

    provides io.vidocq.runtime.spi.config.ConfigSource with
            io.vidocq.runtime.core.config.SystemPropertiesConfigSource,
            io.vidocq.runtime.core.config.EnvConfigSource,
            io.vidocq.runtime.core.config.ExternalFileConfigSource,
            io.vidocq.runtime.core.config.PropertiesFileConfigSource;
}
