module io.vidocq.mpserver.ext.chappe {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.vauban.core;
    requires transitive io.vidocq.chappe.api;
    requires static java.net.http;

    exports io.vidocq.mpserver.ext.chappe;
    exports io.vidocq.mpserver.ext.chappe.spi;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with io.vidocq.mpserver.ext.chappe.ChappeEngineExtension,
                 io.vidocq.mpserver.ext.chappe.ChappeMountConfigExtension,
                 io.vidocq.mpserver.ext.chappe.ChappeServerBootstrap;

    uses io.vidocq.mpserver.ext.chappe.spi.MountHandlerProvider;

    provides io.vidocq.mpserver.ext.chappe.spi.MountHandlerProvider
            with io.vidocq.mpserver.ext.chappe.StaticMountHandlerProvider;
}
