module io.vidocq.mansart.data.dialect.spi {
    requires transitive io.vidocq.mansart.data.api;
    requires java.sql;

    exports io.vidocq.mansart.data.dialect;
    exports io.vidocq.mansart.data.dialect.attribute;

    uses io.vidocq.mansart.data.dialect.DialectFactory;
}
