/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.data.dialect.h2;

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
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * H2 dialect (M3a). Generates ANSI-leaning SQL using H2's {@code MERGE INTO ... KEY (...)} for
 * upsert and {@code IDENTITY} columns for generated keys.
 */
public final class H2Dialect implements Dialect {

    @Override public String name() { return "H2"; }

    @Override
    public SqlFragment select(EntityModel<?> model, Where where, OrderBy orderBy, Pagination pagination) {
        // M8-3 — collect joins from the predicate / orderBy. Empty plan ⇒ classic single-table SQL.
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
        // M8-3 — UPDATE/DELETE statements with joined predicates fall back to a subquery rewrite
        // because ANSI SQL doesn't allow JOIN in UPDATE/DELETE on every backend. For now, throw
        // if a path expression is detected. Single-table predicates work as before.
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
        boolean first = true;
        for (Attribute<?, ?> a : model.attributes()) {
            if (!first) { cols.append(", "); vals.append(", "); }
            cols.append('"').append(a.columnName()).append('"');
            vals.append('?');
            first = false;
        }
        String sql = "MERGE INTO " + qualified(model)
                + " (" + cols + ") KEY (\"" + model.id().columnName() + "\") VALUES (" + vals + ")";
        return new SqlFragment(sql, java.util.List.of());
    }

    /* ---- bind / extract ---- */

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
        // M7-9 — H2 refuses JAVA_OBJECT → CHARACTER VARYING; coerce enums to their name() string
        // so the VARCHAR column declared by ensureTable accepts the value.
        if (value instanceof Enum<?> e) {
            ps.setString(idx, e.name());
            return;
        }
        // Character columns are stored as VARCHAR(255); coerce both Character values and
        // single-char String literals to setString so the JDBC driver doesn't have to bridge
        // CHARACTER VARYING ↔ JAVA_OBJECT (which H2 refuses).
        if (javaType == Character.class) {
            ps.setString(idx, value.toString());
            return;
        }
        if (value instanceof Character ch) {
            ps.setString(idx, String.valueOf(ch));
            return;
        }
        if (value instanceof Instant instant) {
            // Bind an OffsetDateTime: setObject(Instant, TIMESTAMP_WITH_TIMEZONE) is rejected by some drivers.
            ps.setObject(idx, instant.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            return;
        }
        ps.setObject(idx, value, sqlType(javaType));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T extract(ResultSet rs, int idx, Class<T> javaType) throws SQLException {
        if (javaType == String.class)         return (T) rs.getString(idx);
        if (javaType == Boolean.class)        return cast(rs.getBoolean(idx), rs, javaType);
        if (javaType == Byte.class)           return cast(rs.getByte(idx), rs, javaType);
        if (javaType == Short.class)          return cast(rs.getShort(idx), rs, javaType);
        if (javaType == Integer.class)        return cast(rs.getInt(idx), rs, javaType);
        if (javaType == Long.class)           return cast(rs.getLong(idx), rs, javaType);
        if (javaType == Float.class)          return cast(rs.getFloat(idx), rs, javaType);
        if (javaType == Double.class)         return cast(rs.getDouble(idx), rs, javaType);
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
    private <T> T cast(Object primitive, ResultSet rs, Class<T> javaType) throws SQLException {
        return rs.wasNull() ? null : (T) primitive;
    }

    @Override
    public RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state) || "23001".equals(state)) {
            return new jakarta.data.exceptions.EntityExistsException(e.getMessage(), e);
        }
        if ("40001".equals(state)) {
            return new jakarta.data.exceptions.OptimisticLockingFailureException(e.getMessage(), e);
        }
        if (state != null && state.startsWith("23")) {
            return new jakarta.data.exceptions.MappingException(e.getMessage(), e);
        }
        return new io.vidocq.mansart.data.core.MansartDataException("H2 SQL error", e);
    }

    /* ---- helpers ---- */

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
            // The parent of a multi-step path is the prefix without its last hop. For a single
            // hop the parent is the root alias.
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

    /** M8-3 — qualify a column with its owning table alias (root if plain attr, joined alias if joined). */
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
            // M7-27 — case-insensitive comparator wrapper.
            case Where.IgnoreCase w -> renderIgnoreCase(sb, w.inner(), plan);
            // M8-1 — unary scalar function on the column (UPPER/LOWER/LENGTH/ABS).
            case Where.Func w -> renderFunc(sb, w.fn(), w.inner(), plan);
            case Where.AlwaysTrue ignored  -> sb.append("1=1");
            case Where.AlwaysFalse ignored -> sb.append("1=0");
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

    /**
     * M8-1 — render {@code fn(col) <op> ?} for unary scalar JDQL functions. {@code LENGTH} is
     * mapped to the SQL-portable {@code CHAR_LENGTH(...)}.
     */
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
            case Pagination.None ignored -> {}
            case Pagination.Offset o     -> sb.append(" LIMIT ").append(o.limit()).append(" OFFSET ").append(o.offset());
            case Pagination.Keyset k     -> sb.append(" LIMIT ").append(k.limit()); // M3b refines
        }
    }
}
