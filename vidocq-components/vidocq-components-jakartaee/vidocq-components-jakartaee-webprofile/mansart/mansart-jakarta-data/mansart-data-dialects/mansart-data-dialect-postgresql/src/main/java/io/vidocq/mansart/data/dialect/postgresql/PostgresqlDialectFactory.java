package io.vidocq.mansart.data.dialect.postgresql;

import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.DialectFactory;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/** M4 placeholder. Real implementation lands with the PostgreSQL dialect. */
public final class PostgresqlDialectFactory implements DialectFactory {

    @Override public String name() { return "PostgreSQL"; }

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return "PostgreSQL".equalsIgnoreCase(metaData.getDatabaseProductName());
    }

    @Override
    public Dialect create() {
        return new PostgresqlDialect();
    }
}
