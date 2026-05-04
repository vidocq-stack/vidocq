package io.vidocq.mansart.data.dialect;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Discovered via {@code ServiceLoader<DialectFactory>}. The first factory whose
 * {@link #supports(DatabaseMetaData)} returns {@code true} for a given connection wins.
 */
public interface DialectFactory {

    String name();

    boolean supports(DatabaseMetaData metaData) throws SQLException;

    Dialect create();
}
