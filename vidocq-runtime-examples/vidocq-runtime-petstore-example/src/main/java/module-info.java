/**
 * Swagger-style petstore demo: Vidocq + Cassini REST + Mansart Data + Mansart Pool on H2,
 * with JSON-B/JSON-P provided by Champollion. Pet/Category/Tag are flat entities; their
 * relations are composed in {@code PetService} (no JPA associations).
 */
module io.vidocq.runtime.examples.petstore {
    requires java.logging;
    // APT-generated _Pet / Pet_ / PetRepositoryImpl import @Generated (SOURCE retention).
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
    // MicroProfile Config (opt-in).
    requires io.vidocq.runtime.ext.ravel;

    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    requires io.vidocq.mansart.data.core;

    // JAX-RS + Mansart reflect on the resource and entity classes.
    opens io.vidocq.runtime.examples.petstore;
    // JSON-B (Champollion) reflects on the DTO records.
    opens io.vidocq.runtime.examples.petstore.model;
}
