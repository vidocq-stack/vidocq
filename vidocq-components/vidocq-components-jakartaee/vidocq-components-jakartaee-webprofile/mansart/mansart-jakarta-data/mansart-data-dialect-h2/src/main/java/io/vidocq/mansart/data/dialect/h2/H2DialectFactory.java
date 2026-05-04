package io.vidocq.mansart.data.dialect.h2;

import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.DialectFactory;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/** M3 placeholder. Real implementation lands with the H2 dialect. */
public final class H2DialectFactory implements DialectFactory {

    @Override public String name() { return "H2"; }

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return "H2".equalsIgnoreCase(metaData.getDatabaseProductName());
    }

    @Override
    public Dialect create() {
        return new H2Dialect();
    }
}
