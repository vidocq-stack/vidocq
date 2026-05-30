module io.vidocq.runtime.examples.rest {
    requires java.logging;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.ext.rest.cassini;
    // Required by cassini-processor APT output: $$CassiniAdapter implements
    // io.vidocq.cassini.spi.gen.ResourceAdapter and uses InjectionSupport/ParamKind.
    requires io.vidocq.cassini.api;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    // JAX-RS and JSON-B reflect on resources and records.
    opens io.vidocq.runtime.examples.rest;

    // java.util.logging.LogManager instantiates StdoutHandler by reflection via
    // Class.newInstance() when loading logging.properties — requires that
    // the package is exported to java.logging.
    exports io.vidocq.runtime.logging to java.logging;
}
