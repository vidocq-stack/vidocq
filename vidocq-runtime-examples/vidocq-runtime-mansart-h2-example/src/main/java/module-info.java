/**
 * End-to-end demo: Vidocq + Cassini REST + Mansart Data + Mansart Pool on H2 in-memory.
 */
module io.vidocq.runtime.examples.mansart {
    requires java.logging;
    // APT-generated _Product / Product_ / ProductRepositoryImpl import @Generated.
    // SOURCE-retention, only needed at compile time.
    requires static java.compiler;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires jakarta.persistence;
    requires jakarta.data;
    requires jakarta.transaction;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.spi;
    requires io.vidocq.runtime.ext.rest.cassini;
    // Required by cassini-processor APT output: $$CassiniAdapter implements
    // io.vidocq.cassini.spi.gen.ResourceAdapter and uses InjectionSupport/ParamKind.
    requires io.vidocq.cassini.api;
    requires io.vidocq.runtime.ext.mansart.pool;
    requires io.vidocq.runtime.ext.mansart.data;
    requires io.vidocq.runtime.ext.mansart.transactions;
    // MicroProfile Config (opt-in) — provides the RavelConfigSourceProvider which
    // replaces native Vidocq ConfigSources with MP sources at boot, and
    // activates @ConfigProperty via the transitive BCE ravel-cdi-vauban.
    requires io.vidocq.runtime.ext.ravel;

    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    requires io.vidocq.mansart.data.core;

    // JAX-RS reflects on resource classes; JSON-B reflects on the Product record.
    opens io.vidocq.runtime.examples.mansart;
}
