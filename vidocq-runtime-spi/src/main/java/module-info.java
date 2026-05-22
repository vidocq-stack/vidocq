module io.vidocq.runtime.spi {
    requires transitive io.vidocq.vauban.core;

    exports io.vidocq.runtime.spi;
    exports io.vidocq.runtime.spi.config;

    uses io.vidocq.runtime.spi.VidocqExtension;
    uses io.vidocq.runtime.spi.config.ConfigSource;
}
