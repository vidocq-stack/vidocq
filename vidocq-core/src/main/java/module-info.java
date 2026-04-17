module fr.vidocq.vidocq.core {
    requires transitive fr.vidocq.vidocq.spi;
    requires fr.vidocq.vauban.core;

    exports fr.vidocq.vidocq.core;
    exports fr.vidocq.vidocq.core.config;

    uses fr.vidocq.vidocq.spi.VidocqExtension;
    uses fr.vidocq.vidocq.spi.config.ConfigSource;

    provides fr.vidocq.vidocq.spi.config.ConfigSource with
            fr.vidocq.vidocq.core.config.SystemPropertiesConfigSource,
            fr.vidocq.vidocq.core.config.EnvConfigSource,
            fr.vidocq.vidocq.core.config.PropertiesFileConfigSource;
}
