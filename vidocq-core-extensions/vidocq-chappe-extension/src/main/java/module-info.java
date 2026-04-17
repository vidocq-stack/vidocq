module fr.vidocq.vidocq.ext.chappe {
    requires transitive fr.vidocq.vidocq.spi;
    requires fr.vidocq.vauban.core;
    requires transitive fr.vidocq.chappe.api;
    requires static java.net.http;

    exports fr.vidocq.vidocq.ext.chappe;

    provides fr.vidocq.vidocq.spi.VidocqExtension
            with fr.vidocq.vidocq.ext.chappe.ChappeEngineExtension,
                 fr.vidocq.vidocq.ext.chappe.ChappeServerBootstrap;
}
