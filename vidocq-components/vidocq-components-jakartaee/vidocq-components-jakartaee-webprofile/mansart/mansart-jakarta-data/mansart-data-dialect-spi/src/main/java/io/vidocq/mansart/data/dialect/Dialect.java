package io.vidocq.mansart.data.dialect;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Pluggable SQL dialect. One instance per {@link javax.sql.DataSource}; obtained from a
 * {@link DialectFactory} discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Implementations must be stateless and thread-safe — the same {@code Dialect} is used by every
 * virtual thread serving a query against its data source.
 */
public interface Dialect {

    String name();

    SqlFragment select(EntityModel<?> model, Where where, OrderBy orderBy, Pagination pagination);
    SqlFragment insert(EntityModel<?> model, boolean returningGeneratedKey);
    SqlFragment update(EntityModel<?> model, Where where);
    SqlFragment delete(EntityModel<?> model, Where where);
    SqlFragment merge(EntityModel<?> model);

    int sqlType(Class<?> javaType);
    void bind(PreparedStatement ps, int idx, Object value, Class<?> javaType) throws SQLException;
    <T> T extract(ResultSet rs, int idx, Class<T> javaType) throws SQLException;

    /** Maps a JDBC {@link SQLException} to a Jakarta-Data-shaped runtime exception. */
    RuntimeException translate(SQLException e);
}
