import io.vidocq.mpserver.ext.rest.cassini.CassiniExtension;

module io.vidocq.mpserver.ext.rest.cassini {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.mpserver.ext.chappe;
    requires io.vidocq.vauban.core;

    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;
    requires io.vidocq.cassini.chappe;

    requires io.vidocq.chappe.api;
    requires jakarta.cdi;
    requires jakarta.annotation;

    exports io.vidocq.mpserver.ext.rest.cassini;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with CassiniExtension;
}
