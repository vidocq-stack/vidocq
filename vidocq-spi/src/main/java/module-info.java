module io.vidocq.mpserver.spi {
    requires transitive io.vidocq.vauban.core;

    exports io.vidocq.mpserver.spi;
    exports io.vidocq.mpserver.spi.config;

    uses io.vidocq.mpserver.spi.VidocqExtension;
    uses io.vidocq.mpserver.spi.config.ConfigSource;
}
