module io.vidocq.mpserver.ext.servlet.chappe.tck {
    requires transitive io.vidocq.mpserver.ext.servlet.chappe;
    requires transitive fr.vidocq.chappe.api;
    requires transitive jakarta.servlet;
    requires transitive java.net.http;

    exports io.vidocq.mpserver.ext.servlet.chappe.tck;
}
