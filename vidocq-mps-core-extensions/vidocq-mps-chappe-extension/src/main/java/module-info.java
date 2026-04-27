module io.vidocq.mpserver.ext.chappe {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.vauban.core;
    requires transitive fr.vidocq.chappe.api;
    requires static java.net.http;

    exports io.vidocq.mpserver.ext.chappe;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with io.vidocq.mpserver.ext.chappe.ChappeEngineExtension,
                 io.vidocq.mpserver.ext.chappe.ChappeServerBootstrap;
}
