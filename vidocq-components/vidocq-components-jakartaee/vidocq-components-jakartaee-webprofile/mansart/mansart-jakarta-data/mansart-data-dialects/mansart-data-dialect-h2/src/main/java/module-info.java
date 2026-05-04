/**
 * H2 dialect for Mansart Data. Implementation in M3.
 */
module io.vidocq.mansart.data.dialect.h2 {
    requires io.vidocq.mansart.data.dialect.spi;
    requires io.vidocq.mansart.data.core;   // for MansartDataException in translate()
    requires java.sql;

    exports io.vidocq.mansart.data.dialect.h2;

    provides io.vidocq.mansart.data.dialect.DialectFactory
            with io.vidocq.mansart.data.dialect.h2.H2DialectFactory;
}
