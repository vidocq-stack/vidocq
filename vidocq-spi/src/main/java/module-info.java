module fr.vidocq.vidocq.spi {
    requires transitive fr.vidocq.vauban.core;

    exports fr.vidocq.vidocq.spi;
    exports fr.vidocq.vidocq.spi.config;

    uses fr.vidocq.vidocq.spi.VidocqExtension;
    uses fr.vidocq.vidocq.spi.config.ConfigSource;
}
