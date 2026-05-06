/**
 * Runtime engine for Mansart Data — bootstrap, repository runtime, connection scope.
 * Filled in M3.
 */
module io.vidocq.mansart.data.core {
    requires transitive io.vidocq.mansart.data.dialect.spi;
    requires java.sql;
    requires jakarta.data;
    // mansart-data-processor emits @Singleton + @Inject on every *RepositoryImpl, so any
    // downstream user module needs jakarta.inject visible at compile time. Re-exporting
    // it transitively keeps the user module-info minimal.
    requires transitive jakarta.inject;
    // M7-29: JPA mapping annotations are read by name via reflection
    // (RuntimeEntityModelBuilder.hasAnnotation), so jakarta.persistence is not strictly required
    // at runtime. We don't `requires` it here to keep mansart-data-core dep-free.

    exports io.vidocq.mansart.data.core;

    uses io.vidocq.mansart.data.dialect.DialectFactory;
}
