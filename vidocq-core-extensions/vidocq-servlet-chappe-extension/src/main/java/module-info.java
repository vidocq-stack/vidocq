module fr.vidocq.vidocq.ext.servlet.chappe {
    requires transitive fr.vidocq.vidocq.spi;
    requires fr.vidocq.vidocq.ext.chappe;
    requires fr.vidocq.vauban.core;
    requires fr.vidocq.chappe.api;
    requires transitive jakarta.servlet;
    requires jakarta.cdi;
    requires java.xml;
    requires static java.net.http;

    exports fr.vidocq.vidocq.ext.servlet.chappe;

    provides fr.vidocq.vidocq.spi.VidocqExtension
            with fr.vidocq.vidocq.ext.servlet.chappe.VidocqServletChappeExtension;
}
