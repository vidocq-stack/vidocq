module fr.vidocq.vidocq.ext.servlet.chappe.tck {
    requires transitive fr.vidocq.vidocq.ext.servlet.chappe;
    requires transitive fr.vidocq.chappe.api;
    requires transitive jakarta.servlet;
    requires transitive java.net.http;

    exports fr.vidocq.vidocq.ext.servlet.chappe.tck;
}
