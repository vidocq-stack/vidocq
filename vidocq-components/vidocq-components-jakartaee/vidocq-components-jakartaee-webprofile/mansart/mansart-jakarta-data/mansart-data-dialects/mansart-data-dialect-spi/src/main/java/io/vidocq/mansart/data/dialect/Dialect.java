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

    /**
     * Targeted UPDATE for a JDQL {@code UPDATE … SET col = ?} statement — only the listed
     * attributes appear in the SET clause. Default delegates to {@link #update(EntityModel, Where)}
     * (which sets every attribute), which is suboptimal — dialects should override.
     */
    default SqlFragment updateSet(EntityModel<?> model, java.util.List<Attribute<?, ?>> attributes, Where where) {
        StringBuilder sb = new StringBuilder("UPDATE \"").append(model.tableName()).append("\" SET ");
        for (int i = 0; i < attributes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(attributes.get(i).columnName()).append("\" = ?");
        }
        if (!(where instanceof Where.AlwaysTrue)) {
            sb.append(" WHERE ");
            // Reuse the existing rendering by going through update().
            String selSql = update(model, where).sql();
            int idx = selSql.indexOf(" WHERE ");
            if (idx >= 0) sb.append(selSql.substring(idx + " WHERE ".length()));
        }
        return new SqlFragment(sb.toString(), java.util.List.of());
    }

    int sqlType(Class<?> javaType);
    void bind(PreparedStatement ps, int idx, Object value, Class<?> javaType) throws SQLException;
    <T> T extract(ResultSet rs, int idx, Class<T> javaType) throws SQLException;

    /** Maps a JDBC {@link SQLException} to a Jakarta-Data-shaped runtime exception. */
    RuntimeException translate(SQLException e);
}
