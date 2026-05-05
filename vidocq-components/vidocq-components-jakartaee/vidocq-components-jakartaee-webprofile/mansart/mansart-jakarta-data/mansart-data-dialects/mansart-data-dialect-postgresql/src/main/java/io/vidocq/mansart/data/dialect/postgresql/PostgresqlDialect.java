package io.vidocq.mansart.data.dialect.postgresql;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.OrderBy;
import io.vidocq.mansart.data.dialect.Pagination;
import io.vidocq.mansart.data.dialect.SqlFragment;
import io.vidocq.mansart.data.dialect.Where;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * PostgreSQL dialect (M4). Mostly ANSI-compliant; the only structural divergence vs H2 is the
 * upsert form: {@code INSERT … ON CONFLICT ("id") DO UPDATE SET col = EXCLUDED.col}.
 */
public final class PostgresqlDialect implements Dialect {

    @Override public String name() { return "PostgreSQL"; }

    @Override
    public SqlFragment select(EntityModel<?> model, Where where, OrderBy orderBy, Pagination pagination) {
        StringBuilder sb = new StringBuilder("SELECT ");
        appendColumnList(sb, model);
        sb.append(" FROM ").append(qualified(model));
        appendWhere(sb, where);
        appendOrderBy(sb, orderBy);
        appendPagination(sb, pagination);
        return new SqlFragment(sb.toString(), java.util.List.of());
    }

    @Override
    public SqlFragment insert(EntityModel<?> model, boolean returningGeneratedKey) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (a == model.id() && model.id().generated()) continue;
            if (!first) { cols.append(", "); vals.append(", "); }
            cols.append('"').append(a.columnName()).append('"');
            vals.append('?');
            first = false;
        }
        String sql = "INSERT INTO " + qualified(model) + " (" + cols + ") VALUES (" + vals + ")";
        return new SqlFragment(sql, java.util.List.of());
    }

    @Override
    public SqlFragment update(EntityModel<?> model, Where where) {
        StringBuilder sb = new StringBuilder("UPDATE ").append(qualified(model)).append(" SET ");
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (a == model.id()) continue;
            if (!first) sb.append(", ");
            sb.append('"').append(a.columnName()).append("\" = ?");
            first = false;
        }
        appendWhere(sb, where);
        return new SqlFragment(sb.toString(), java.util.List.of());
    }

    @Override
    public SqlFragment delete(EntityModel<?> model, Where where) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(qualified(model));
        appendWhere(sb, where);
        return new SqlFragment(sb.toString(), java.util.List.of());
    }

    @Override
    public SqlFragment merge(EntityModel<?> model) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        StringBuilder set  = new StringBuilder();
        boolean firstCol = true, firstSet = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (!firstCol) { cols.append(", "); vals.append(", "); }
            cols.append('"').append(a.columnName()).append('"');
            vals.append('?');
            firstCol = false;
            if (a == model.id()) continue;
            if (!firstSet) set.append(", ");
            set.append('"').append(a.columnName()).append("\" = EXCLUDED.\"")
               .append(a.columnName()).append('"');
            firstSet = false;
        }
        String sql = "INSERT INTO " + qualified(model)
                + " (" + cols + ") VALUES (" + vals + ") "
                + "ON CONFLICT (\"" + model.id().columnName() + "\") DO UPDATE SET " + set;
        return new SqlFragment(sql, java.util.List.of());
    }

    /* ---- bind / extract — identical to H2 (PG driver honours JDBC Types semantics) ---- */

    @Override
    public int sqlType(Class<?> javaType) {
        if (javaType == String.class)         return Types.VARCHAR;
        if (javaType == Boolean.class)        return Types.BOOLEAN;
        if (javaType == Byte.class)           return Types.TINYINT;
        if (javaType == Short.class)          return Types.SMALLINT;
        if (javaType == Integer.class)        return Types.INTEGER;
        if (javaType == Long.class)           return Types.BIGINT;
        if (javaType == Float.class)          return Types.REAL;
        if (javaType == Double.class)         return Types.DOUBLE;
        if (javaType == BigDecimal.class)     return Types.NUMERIC;
        if (javaType == LocalDate.class)      return Types.DATE;
        if (javaType == LocalTime.class)      return Types.TIME;
        if (javaType == LocalDateTime.class)  return Types.TIMESTAMP;
        if (javaType == OffsetDateTime.class
                || javaType == Instant.class) return Types.TIMESTAMP_WITH_TIMEZONE;
        if (javaType == UUID.class)           return Types.OTHER;
        if (javaType.isEnum())                return Types.VARCHAR;
        return Types.OTHER;
    }

    @Override
    public void bind(PreparedStatement ps, int idx, Object value, Class<?> javaType) throws SQLException {
        if (value == null) {
            ps.setNull(idx, sqlType(javaType));
            return;
        }
        if (value instanceof Enum<?> e) {
            ps.setString(idx, e.name());
            return;
        }
        ps.setObject(idx, value, sqlType(javaType));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T extract(ResultSet rs, int idx, Class<T> javaType) throws SQLException {
        if (javaType == String.class)         return (T) rs.getString(idx);
        if (javaType == Boolean.class)        return cast(rs.getBoolean(idx), rs);
        if (javaType == Byte.class)           return cast(rs.getByte(idx), rs);
        if (javaType == Short.class)          return cast(rs.getShort(idx), rs);
        if (javaType == Integer.class)        return cast(rs.getInt(idx), rs);
        if (javaType == Long.class)           return cast(rs.getLong(idx), rs);
        if (javaType == Float.class)          return cast(rs.getFloat(idx), rs);
        if (javaType == Double.class)         return cast(rs.getDouble(idx), rs);
        if (javaType == BigDecimal.class)     return (T) rs.getBigDecimal(idx);
        if (javaType == LocalDate.class)      return (T) rs.getObject(idx, LocalDate.class);
        if (javaType == LocalTime.class)      return (T) rs.getObject(idx, LocalTime.class);
        if (javaType == LocalDateTime.class)  return (T) rs.getObject(idx, LocalDateTime.class);
        if (javaType == OffsetDateTime.class) return (T) rs.getObject(idx, OffsetDateTime.class);
        if (javaType == Instant.class) {
            OffsetDateTime odt = rs.getObject(idx, OffsetDateTime.class);
            return odt == null ? null : (T) odt.toInstant();
        }
        if (javaType == UUID.class)           return (T) rs.getObject(idx, UUID.class);
        return rs.getObject(idx, javaType);
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object primitive, ResultSet rs) throws SQLException {
        return rs.wasNull() ? null : (T) primitive;
    }

    @Override
    public RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state)) {
            return new jakarta.data.exceptions.EntityExistsException(e.getMessage(), e);
        }
        if ("40001".equals(state)) {
            return new jakarta.data.exceptions.OptimisticLockingFailureException(e.getMessage(), e);
        }
        if (state != null && state.startsWith("23")) {
            return new jakarta.data.exceptions.MappingException(e.getMessage(), e);
        }
        return new io.vidocq.mansart.data.core.MansartDataException("PostgreSQL SQL error", e);
    }

    /* ---- shared helpers (mirror H2 — refactored into a base class in M5+) ---- */

    private String qualified(EntityModel<?> model) {
        return (model.schema() == null || model.schema().isEmpty())
                ? "\"" + model.tableName() + "\""
                : "\"" + model.schema() + "\".\"" + model.tableName() + "\"";
    }

    private void appendColumnList(StringBuilder sb, EntityModel<?> model) {
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (!first) sb.append(", ");
            sb.append('"').append(a.columnName()).append('"');
            first = false;
        }
    }

    private void appendWhere(StringBuilder sb, Where where) {
        if (where instanceof Where.AlwaysTrue) return;
        sb.append(" WHERE ");
        renderPredicate(sb, where);
    }

    private void renderPredicate(StringBuilder sb, Where where) {
        switch (where) {
            case Where.Eq w        -> sb.append('"').append(w.attr().columnName()).append("\" = ?");
            case Where.NotEq w     -> sb.append('"').append(w.attr().columnName()).append("\" <> ?");
            case Where.Lt w        -> sb.append('"').append(w.attr().columnName()).append("\" < ?");
            case Where.Lte w       -> sb.append('"').append(w.attr().columnName()).append("\" <= ?");
            case Where.Gt w        -> sb.append('"').append(w.attr().columnName()).append("\" > ?");
            case Where.Gte w       -> sb.append('"').append(w.attr().columnName()).append("\" >= ?");
            case Where.Like w      -> sb.append('"').append(w.attr().columnName()).append("\" LIKE ?");
            case Where.IsNull w    -> sb.append('"').append(w.attr().columnName()).append("\" IS NULL");
            case Where.IsNotNull w -> sb.append('"').append(w.attr().columnName()).append("\" IS NOT NULL");
            case Where.Between w   -> sb.append('"').append(w.attr().columnName()).append("\" BETWEEN ? AND ?");
            case Where.In w -> {
                sb.append('"').append(w.attr().columnName()).append("\" IN (");
                for (int i = 0; i < w.arity(); i++) { if (i > 0) sb.append(", "); sb.append('?'); }
                sb.append(')');
            }
            case Where.And w -> {
                sb.append('(');
                for (int i = 0; i < w.children().size(); i++) {
                    if (i > 0) sb.append(" AND ");
                    renderPredicate(sb, w.children().get(i));
                }
                sb.append(')');
            }
            case Where.Or w -> {
                sb.append('(');
                for (int i = 0; i < w.children().size(); i++) {
                    if (i > 0) sb.append(" OR ");
                    renderPredicate(sb, w.children().get(i));
                }
                sb.append(')');
            }
            case Where.Not w -> {
                sb.append("NOT (");
                renderPredicate(sb, w.child());
                sb.append(')');
            }
            case Where.AlwaysTrue _  -> sb.append("TRUE");
            case Where.AlwaysFalse _ -> sb.append("FALSE");
        }
    }

    private void appendOrderBy(StringBuilder sb, OrderBy orderBy) {
        if (orderBy.isEmpty()) return;
        sb.append(" ORDER BY ");
        for (int i = 0; i < orderBy.orders().size(); i++) {
            var o = orderBy.orders().get(i);
            if (i > 0) sb.append(", ");
            sb.append('"').append(o.attr().columnName()).append("\" ").append(o.direction());
        }
    }

    private void appendPagination(StringBuilder sb, Pagination p) {
        switch (p) {
            case Pagination.None _ -> { /* NOOP */ }
            case Pagination.Offset o     -> sb.append(" LIMIT ").append(o.limit()).append(" OFFSET ").append(o.offset());
            case Pagination.Keyset k     -> sb.append(" LIMIT ").append(k.limit());
        }
    }
}
