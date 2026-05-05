package io.vidocq.mansart.data.dialect.postgresql;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.Joins;
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
        Joins.Plan plan = Joins.collect(where, orderBy);
        StringBuilder sb = new StringBuilder("SELECT ");
        if (plan.isEmpty()) {
            appendColumnList(sb, model);
            sb.append(" FROM ").append(qualified(model));
            appendWhere(sb, where, plan);
            appendOrderBy(sb, orderBy, plan);
        } else {
            appendAliasedColumnList(sb, model, Joins.ROOT_ALIAS);
            sb.append(" FROM ").append(qualified(model)).append(' ').append(Joins.ROOT_ALIAS);
            appendJoins(sb, plan);
            appendWhere(sb, where, plan);
            appendOrderBy(sb, orderBy, plan);
        }
        appendPagination(sb, pagination);
        return new SqlFragment(sb.toString(), java.util.List.of());
    }

    @Override
    public SqlFragment selectColumns(EntityModel<?> model, java.util.List<ProjectedColumn> columns,
                                     Where where, OrderBy orderBy, Pagination pagination) {
        Joins.Plan plan = Joins.collect(where, orderBy);
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            switch (columns.get(i)) {
                case ProjectedColumn.Leaf leaf -> sb.append(col(leaf.attr(), plan));
                case ProjectedColumn.Expr exp  -> sb.append(exp.sqlFragment());
            }
        }
        sb.append(" FROM ").append(qualified(model));
        if (!plan.isEmpty()) {
            sb.append(' ').append(Joins.ROOT_ALIAS);
            appendJoins(sb, plan);
        }
        appendWhere(sb, where, plan);
        appendOrderBy(sb, orderBy, plan);
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
        Joins.Plan plan = Joins.collect(where, OrderBy.NONE);
        if (!plan.isEmpty()) throw new IllegalStateException(
                "UPDATE with joined predicate is not yet supported (path: " + plan.aliasByPath().keySet() + ")");
        StringBuilder sb = new StringBuilder("UPDATE ").append(qualified(model)).append(" SET ");
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (a == model.id()) continue;
            if (!first) sb.append(", ");
            sb.append('"').append(a.columnName()).append("\" = ?");
            first = false;
        }
        appendWhere(sb, where, plan);
        return new SqlFragment(sb.toString(), java.util.List.of());
    }

    @Override
    public SqlFragment delete(EntityModel<?> model, Where where) {
        Joins.Plan plan = Joins.collect(where, OrderBy.NONE);
        if (!plan.isEmpty()) throw new IllegalStateException(
                "DELETE with joined predicate is not yet supported (path: " + plan.aliasByPath().keySet() + ")");
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(qualified(model));
        appendWhere(sb, where, plan);
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
        if (javaType == Character.class) {
            ps.setString(idx, value.toString());
            return;
        }
        if (value instanceof Character ch) {
            ps.setString(idx, String.valueOf(ch));
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
        if (javaType.isEnum()) {
            String s = rs.getString(idx);
            if (s == null) return null;
            @SuppressWarnings({"rawtypes", "unchecked"})
            T v = (T) Enum.valueOf((Class) javaType, s);
            return v;
        }
        if (javaType == Character.class) {
            String s = rs.getString(idx);
            return (s == null || s.isEmpty()) ? null : (T) Character.valueOf(s.charAt(0));
        }
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

    /** M8-3 — column list aliased on {@code rootAlias} for joined queries. */
    private void appendAliasedColumnList(StringBuilder sb, EntityModel<?> model, String rootAlias) {
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (!first) sb.append(", ");
            sb.append(rootAlias).append(".\"").append(a.columnName()).append('"');
            first = false;
        }
    }

    /** M8-3 — emit one {@code INNER JOIN target tN ON parentAlias."fk" = tN."pk"} per step. */
    private void appendJoins(StringBuilder sb, Joins.Plan plan) {
        for (var entry : plan.entriesInOrder()) {
            var path = entry.path();
            String alias = entry.alias();
            String parentAlias = path.steps().size() == 1
                    ? Joins.ROOT_ALIAS
                    : plan.aliasByPath().get(path.prefix(path.steps().size() - 1));
            var step = path.steps().get(path.steps().size() - 1);
            sb.append(" INNER JOIN ");
            if (!step.targetSchemaName().isEmpty()) sb.append('"').append(step.targetSchemaName()).append("\".");
            sb.append('"').append(step.targetTableName()).append("\" ").append(alias)
              .append(" ON ").append(parentAlias).append(".\"").append(step.foreignKeyColumn()).append('"')
              .append(" = ").append(alias).append(".\"").append(step.referencedColumn()).append('"');
        }
    }

    private String col(Attribute<?, ?> a, Joins.Plan plan) {
        if (plan.isEmpty()) return "\"" + a.columnName() + "\"";
        return plan.tableAliasFor(a) + ".\"" + a.columnName() + "\"";
    }

    private void appendWhere(StringBuilder sb, Where where, Joins.Plan plan) {
        if (where instanceof Where.AlwaysTrue) return;
        sb.append(" WHERE ");
        renderPredicate(sb, where, plan);
    }

    private void renderPredicate(StringBuilder sb, Where where, Joins.Plan plan) {
        switch (where) {
            case Where.Eq w        -> sb.append(col(w.attr(), plan)).append(" = ?");
            case Where.NotEq w     -> sb.append(col(w.attr(), plan)).append(" <> ?");
            case Where.Lt w        -> sb.append(col(w.attr(), plan)).append(" < ?");
            case Where.Lte w       -> sb.append(col(w.attr(), plan)).append(" <= ?");
            case Where.Gt w        -> sb.append(col(w.attr(), plan)).append(" > ?");
            case Where.Gte w       -> sb.append(col(w.attr(), plan)).append(" >= ?");
            case Where.Like w      -> sb.append(col(w.attr(), plan)).append(" LIKE ?");
            case Where.IsNull w    -> sb.append(col(w.attr(), plan)).append(" IS NULL");
            case Where.IsNotNull w -> sb.append(col(w.attr(), plan)).append(" IS NOT NULL");
            case Where.Between w   -> sb.append(col(w.attr(), plan)).append(" BETWEEN ? AND ?");
            case Where.In w -> {
                sb.append(col(w.attr(), plan)).append(" IN (");
                for (int i = 0; i < w.arity(); i++) { if (i > 0) sb.append(", "); sb.append('?'); }
                sb.append(')');
            }
            case Where.And w -> {
                sb.append('(');
                for (int i = 0; i < w.children().size(); i++) {
                    if (i > 0) sb.append(" AND ");
                    renderPredicate(sb, w.children().get(i), plan);
                }
                sb.append(')');
            }
            case Where.Or w -> {
                sb.append('(');
                for (int i = 0; i < w.children().size(); i++) {
                    if (i > 0) sb.append(" OR ");
                    renderPredicate(sb, w.children().get(i), plan);
                }
                sb.append(')');
            }
            case Where.Not w -> {
                sb.append("NOT (");
                renderPredicate(sb, w.child(), plan);
                sb.append(')');
            }
            case Where.IgnoreCase w -> renderIgnoreCase(sb, w.inner(), plan);
            case Where.Func w -> renderFunc(sb, w.fn(), w.inner(), plan);
            case Where.AlwaysTrue _  -> sb.append("TRUE");
            case Where.AlwaysFalse _ -> sb.append("FALSE");
        }
    }

    private void renderIgnoreCase(StringBuilder sb, Where inner, Joins.Plan plan) {
        switch (inner) {
            case Where.Eq w      -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") = LOWER(?)");
            case Where.NotEq w   -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") <> LOWER(?)");
            case Where.Like w    -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") LIKE LOWER(?)");
            case Where.Lt w      -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") < LOWER(?)");
            case Where.Lte w     -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") <= LOWER(?)");
            case Where.Gt w      -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") > LOWER(?)");
            case Where.Gte w     -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") >= LOWER(?)");
            case Where.Between w -> sb.append("LOWER(").append(col(w.attr(), plan)).append(") BETWEEN LOWER(?) AND LOWER(?)");
            case Where.In w -> {
                sb.append("LOWER(").append(col(w.attr(), plan)).append(") IN (");
                for (int i = 0; i < w.arity(); i++) { if (i > 0) sb.append(", "); sb.append("LOWER(?)"); }
                sb.append(')');
            }
            case Where.Not w -> {
                sb.append("NOT (");
                renderIgnoreCase(sb, w.child(), plan);
                sb.append(')');
            }
            default -> throw new IllegalArgumentException(
                    "IgnoreCase wraps Eq/NotEq/Lt/Lte/Gt/Gte/Like/Between/In/Not only, got: " + inner);
        }
    }

    private void renderFunc(StringBuilder sb, String fn, Where inner, Joins.Plan plan) {
        String sqlFn = "LENGTH".equals(fn) ? "CHAR_LENGTH" : fn;
        switch (inner) {
            case Where.Eq w      -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " = ?");
            case Where.NotEq w   -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " <> ?");
            case Where.Like w    -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " LIKE ?");
            case Where.Lt w      -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " < ?");
            case Where.Lte w     -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " <= ?");
            case Where.Gt w      -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " > ?");
            case Where.Gte w     -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " >= ?");
            case Where.Between w -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " BETWEEN ? AND ?");
            case Where.IsNull w    -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " IS NULL");
            case Where.IsNotNull w -> appendFnLhs(sb, sqlFn, col(w.attr(), plan), " IS NOT NULL");
            case Where.In w -> {
                appendFnLhs(sb, sqlFn, col(w.attr(), plan), " IN (");
                for (int i = 0; i < w.arity(); i++) { if (i > 0) sb.append(", "); sb.append('?'); }
                sb.append(')');
            }
            case Where.Not w -> {
                sb.append("NOT (");
                renderFunc(sb, fn, w.child(), plan);
                sb.append(')');
            }
            default -> throw new IllegalArgumentException(
                    "Func wraps Eq/NotEq/Lt/Lte/Gt/Gte/Like/Between/In/IsNull/IsNotNull/Not only, got: " + inner);
        }
    }

    private void appendFnLhs(StringBuilder sb, String sqlFn, String qualifiedColumn, String tail) {
        sb.append(sqlFn).append('(').append(qualifiedColumn).append(')').append(tail);
    }

    private void appendOrderBy(StringBuilder sb, OrderBy orderBy, Joins.Plan plan) {
        if (orderBy.isEmpty()) return;
        sb.append(" ORDER BY ");
        for (int i = 0; i < orderBy.orders().size(); i++) {
            var o = orderBy.orders().get(i);
            if (i > 0) sb.append(", ");
            sb.append(col(o.attr(), plan)).append(' ').append(o.direction());
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
