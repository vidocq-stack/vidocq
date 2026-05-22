module io.vidocq.runtime.examples.rest {
    requires java.logging;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.ext.rest.cassini;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    // JAX-RS et JSON-B font de la reflection sur ressources et records.
    opens io.vidocq.runtime.examples.rest;

    // java.util.logging.LogManager instancie StdoutHandler par réflexion via
    // Class.newInstance() lors du load de logging.properties — requiert que
    // le package soit exporté à java.logging.
    exports io.vidocq.runtime.logging to java.logging;
}
