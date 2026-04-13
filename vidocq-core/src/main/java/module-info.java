module fr.vidocq.vidocq.core {
    requires transitive fr.vidocq.vidocq.spi;
    requires fr.vidocq.vauban.core;

    exports fr.vidocq.vidocq.core;

    uses fr.vidocq.vidocq.spi.VidocqExtension;
}
