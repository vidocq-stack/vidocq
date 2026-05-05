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

    /**
     * M8-3 — variant of {@link #select} that selects a custom column list (one or many) instead
     * of the entity's full row. Used by JDQL aggregates (passing a single {@code aggExpr}-shaped
     * column entry), single-attribute projections, and multi-attribute projections — all of
     * which may now contain {@link io.vidocq.mansart.data.dialect.attribute.JoinedAttribute}
     * leaves. The dialect handles join collection, aliasing, and SQL rendering uniformly.
     *
     * <p>Each {@link ProjectedColumn} carries either a leaf attribute (rendered as
     * {@code aliasedColumn}) or a raw SQL fragment (e.g. {@code "MAX(\"id\")"}, used for
     * aggregates). The fragment must already be SQL-safe — Mansart never mixes user input here.
     */
    default SqlFragment selectColumns(EntityModel<?> model, java.util.List<ProjectedColumn> columns,
                                      Where where, OrderBy orderBy, Pagination pagination) {
        // Default delegates rendering to a uniform helper that mirrors {@link #select}'s plan-aware
        // rendering. Concrete dialects may override for backend-specific behaviour. This default
        // does NOT support joined columns — concrete dialects must override to support M8-3 paths.
        throw new UnsupportedOperationException("selectColumns not implemented by " + name());
    }

    SqlFragment insert(EntityModel<?> model, boolean returningGeneratedKey);
    SqlFragment update(EntityModel<?> model, Where where);
    SqlFragment delete(EntityModel<?> model, Where where);
    SqlFragment merge(EntityModel<?> model);

    /**
     * M8-3 — describes a single SELECT-list entry for {@link #selectColumns}. Either a leaf
     * attribute (column name resolved by the dialect, possibly aliased through a join plan) or
     * a pre-formed SQL expression (used for aggregates like {@code COUNT(*)} or {@code MAX("id")}).
     */
    sealed interface ProjectedColumn {
        record Leaf(Attribute<?, ?> attr) implements ProjectedColumn {}
        record Expr(String sqlFragment) implements ProjectedColumn {}
    }

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
