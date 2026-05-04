package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.DialectFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ServiceLoader;

/**
 * Discovers the appropriate {@link Dialect} for a given {@link DataSource} via
 * {@code ServiceLoader<DialectFactory>}. The first factory whose {@code supports(...)} returns
 * {@code true} for the data source's metadata wins.
 *
 * <p>The lookup queries {@link Connection#getMetaData()} once at bootstrap and caches the dialect.
 */
final class DialectResolver {

    private DialectResolver() {}

    static Dialect resolve(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            var md = c.getMetaData();
            for (DialectFactory factory : ServiceLoader.load(DialectFactory.class)) {
                if (factory.supports(md)) return factory.create();
            }
            throw new MansartDataException("No DialectFactory accepts database product '"
                    + md.getDatabaseProductName() + "'. Add a mansart-data-dialect-* JAR to the module path.");
        } catch (java.sql.SQLException e) {
            throw new MansartDataException("Failed to detect dialect from DataSource", e);
        }
    }
}
