module acme.reload {
    // What Cassini's processor generates imports @Generated.
    requires static java.compiler;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires org.eclipse.microprofile.config;
    requires io.vidocq.cassini.api;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    requires io.vidocq.runtime.extensions.microprofile.ravel;

    // Cassini and JSON-B reflect on the resource and on the record it returns.
    opens acme.reload;
}
