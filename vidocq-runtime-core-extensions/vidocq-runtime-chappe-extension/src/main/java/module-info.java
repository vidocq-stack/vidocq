module io.vidocq.runtime.ext.chappe {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires transitive io.vidocq.chappe.api;
    requires static java.net.http;

    exports io.vidocq.runtime.ext.chappe;
    exports io.vidocq.runtime.ext.chappe.spi;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.ext.chappe.ChappeEngineExtension,
                 io.vidocq.runtime.ext.chappe.ChappeMountConfigExtension,
                 io.vidocq.runtime.ext.chappe.ChappeServerBootstrap;

    uses io.vidocq.runtime.ext.chappe.spi.MountHandlerProvider;

    provides io.vidocq.runtime.ext.chappe.spi.MountHandlerProvider
            with io.vidocq.runtime.ext.chappe.StaticMountHandlerProvider;
}
