import io.vidocq.runtime.ext.rest.cassini.CassiniExtension;
import io.vidocq.runtime.ext.rest.cassini.CassiniMountHandlerProvider;

module io.vidocq.runtime.ext.rest.cassini {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.runtime.ext.chappe;
    requires io.vidocq.vauban.core;

    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.cassini.cdi.vauban;

    requires io.vidocq.chappe.api;
    requires jakarta.cdi;
    requires jakarta.annotation;

    exports io.vidocq.runtime.ext.rest.cassini;

    provides io.vidocq.runtime.spi.VidocqExtension
            with CassiniExtension;

    provides io.vidocq.runtime.ext.chappe.spi.MountHandlerProvider
            with CassiniMountHandlerProvider;
}
