/**
 * Implémentation MicroProfile Config 3.1 standalone — utilisable en SE pur,
 * sans CDI ni container. Les sources, converters et resolver sont contribués
 * via ServiceLoader.
 */
module io.vidocq.ravel.core {
    requires transitive io.vidocq.ravel.api;

    provides org.eclipse.microprofile.config.spi.ConfigProviderResolver
            with io.vidocq.ravel.internal.RavelConfigProviderResolver;

    uses org.eclipse.microprofile.config.spi.ConfigSource;
    uses org.eclipse.microprofile.config.spi.ConfigSourceProvider;
    uses org.eclipse.microprofile.config.spi.Converter;
}
