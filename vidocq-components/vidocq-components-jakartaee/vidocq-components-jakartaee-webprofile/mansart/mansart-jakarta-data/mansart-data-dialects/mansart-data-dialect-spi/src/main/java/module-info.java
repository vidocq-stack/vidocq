module io.vidocq.mansart.data.dialect.spi {
    requires java.sql;
    // M7-29 — jakarta.data is part of the SPI surface (jakarta.data.exceptions thrown by
    // Dialect.translate, jakarta.data.page types referenced by Pagination). Made transitive
    // so dialect implementations (h2/postgresql) automatically inherit visibility.
    requires transitive jakarta.data;

    exports io.vidocq.mansart.data.dialect;
    exports io.vidocq.mansart.data.dialect.attribute;

    uses io.vidocq.mansart.data.dialect.DialectFactory;
}
