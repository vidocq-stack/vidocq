/**
 * Ravel API module: controlled re-export of the MicroProfile Config 3.1 spec and
 * stable public SPI for third-party extensions (configuration sources, custom
 * converters, observability hooks).
 */
module io.vidocq.ravel.api {
    requires transitive org.eclipse.microprofile.config;

    exports io.vidocq.ravel.spi;
}
