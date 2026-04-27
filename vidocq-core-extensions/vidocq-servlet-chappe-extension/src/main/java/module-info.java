module io.vidocq.mpserver.ext.servlet.chappe {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.mpserver.ext.chappe;
    requires io.vidocq.vauban.core;
    requires fr.vidocq.chappe.api;
    requires transitive jakarta.servlet;
    requires jakarta.cdi;
    requires java.xml;
    requires static java.net.http;

    exports io.vidocq.mpserver.ext.servlet.chappe;
    exports io.vidocq.mpserver.ext.servlet.chappe.bridge;
    exports io.vidocq.mpserver.ext.servlet.chappe.container;
    exports io.vidocq.mpserver.ext.servlet.chappe.dispatcher;
    exports io.vidocq.mpserver.ext.servlet.chappe.error;
    exports io.vidocq.mpserver.ext.servlet.chappe.http;
    exports io.vidocq.mpserver.ext.servlet.chappe.listener;
    exports io.vidocq.mpserver.ext.servlet.chappe.security;
    exports io.vidocq.mpserver.ext.servlet.chappe.session;
    exports io.vidocq.mpserver.ext.servlet.chappe.webxml;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with io.vidocq.mpserver.ext.servlet.chappe.VidocqServletChappeExtension;
}
