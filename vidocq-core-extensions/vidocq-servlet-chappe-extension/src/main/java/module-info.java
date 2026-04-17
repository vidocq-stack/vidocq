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
    exports fr.vidocq.vidocq.ext.servlet.chappe.bridge;
    exports fr.vidocq.vidocq.ext.servlet.chappe.container;
    exports fr.vidocq.vidocq.ext.servlet.chappe.dispatcher;
    exports fr.vidocq.vidocq.ext.servlet.chappe.error;
    exports fr.vidocq.vidocq.ext.servlet.chappe.http;
    exports fr.vidocq.vidocq.ext.servlet.chappe.listener;
    exports fr.vidocq.vidocq.ext.servlet.chappe.security;
    exports fr.vidocq.vidocq.ext.servlet.chappe.session;
    exports fr.vidocq.vidocq.ext.servlet.chappe.webxml;

    provides fr.vidocq.vidocq.spi.VidocqExtension
            with fr.vidocq.vidocq.ext.servlet.chappe.VidocqServletChappeExtension;
}
