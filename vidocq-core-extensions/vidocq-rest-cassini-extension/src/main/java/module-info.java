import fr.vidocq.vidocq.ext.rest.cassini.CassiniExtension;
import fr.vidocq.vidocq.ext.rest.cassini.CassiniScopeBCE;

module fr.vidocq.vidocq.ext.rest.cassini {
    requires transitive fr.vidocq.vidocq.spi;
    requires fr.vidocq.vidocq.ext.chappe;
    requires fr.vidocq.vauban.core;
    requires fr.vidocq.chappe.api;

    requires transitive jakarta.ws.rs;
    requires jakarta.cdi;
    requires jakarta.annotation;

    requires static java.net.http;

    exports fr.vidocq.vidocq.ext.rest.cassini;

    opens fr.vidocq.vidocq.ext.rest.cassini to fr.vidocq.vauban.core;

    provides fr.vidocq.vidocq.spi.VidocqExtension
            with CassiniExtension;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with CassiniScopeBCE;
}
