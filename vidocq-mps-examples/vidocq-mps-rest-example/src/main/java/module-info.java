module io.vidocq.mpserver.examples.rest {
    requires java.logging;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;

    requires io.vidocq.mpserver.core;
    requires io.vidocq.mpserver.spi;
    requires io.vidocq.mpserver.ext.rest.cassini;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    // JAX-RS et JSON-B font de la reflection sur ressources et records.
    opens io.vidocq.mpserver.examples.rest;
}
