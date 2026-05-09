module io.vidocq.mpserver.core {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.vauban.core;
    requires java.management;
    // Ravel MicroProfile Config 3.1 — auto-découverte BCE via ServiceLoader
    requires transitive io.vidocq.ravel.cdi.vauban;

    exports io.vidocq.mpserver.core;
    exports io.vidocq.mpserver.core.config;

    uses io.vidocq.mpserver.spi.VidocqExtension;
    uses io.vidocq.mpserver.spi.config.ConfigSource;

    provides io.vidocq.mpserver.spi.config.ConfigSource with
            io.vidocq.mpserver.core.config.SystemPropertiesConfigSource,
            io.vidocq.mpserver.core.config.EnvConfigSource,
            io.vidocq.mpserver.core.config.ExternalFileConfigSource,
            io.vidocq.mpserver.core.config.PropertiesFileConfigSource;
}
