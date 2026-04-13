module fr.vidocq.vidocq.spi {
    requires transitive fr.vidocq.vauban.core;

    exports fr.vidocq.vidocq.spi;

    uses fr.vidocq.vidocq.spi.VidocqExtension;
}
