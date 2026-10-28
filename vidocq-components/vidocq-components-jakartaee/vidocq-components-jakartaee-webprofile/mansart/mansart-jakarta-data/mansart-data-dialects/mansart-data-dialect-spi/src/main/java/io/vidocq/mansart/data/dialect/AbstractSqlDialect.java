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
package io.vidocq.mansart.data.dialect;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SQL rendering, binding and extraction that the H2 and PostgreSQL dialects share: quoted
 * identifiers, {@code INNER JOIN} plans for path predicates, {@code LIMIT … OFFSET …} pagination and
 * the standard JDBC type mapping. A concrete dialect supplies its {@link #name()}, its upsert form
 * ({@link #merge}), its SQLState translation ({@link #translate}) and the literals it renders for
 * the constant predicates.
 */
public abstract class AbstractSqlDialect implements Dialect {

    private static final String FROM = " FROM ";
    private static final String NOT_OPEN = "NOT (";
    private static final String LOWER = "LOWER";
    private static final String IN_OPEN = " IN (";

    /** JDBC type of each Java type with a fixed mapping; enums and unknown types are handled apart. */
    private static final Map<Class<?>, Integer> SQL_TYPES = Map.ofEntries(
            Map.entry(String.class, Types.VARCHAR),
            Map.entry(Boolean.class, Types.BOOLEAN),
            Map.entry(Byte.class, Types.TINYINT),
            Map.entry(Short.class, Types.SMALLINT),
            Map.entry(Integer.class, Types.INTEGER),
            Map.entry(Long.class, Types.BIGINT),
            Map.entry(Float.class, Types.REAL),
            Map.entry(Double.class, Types.DOUBLE),
            Map.entry(BigDecimal.class, Types.NUMERIC),
            Map.entry(LocalDate.class, Types.DATE),
            Map.entry(LocalTime.class, Types.TIME),
            Map.entry(LocalDateTime.class, Types.TIMESTAMP),
            Map.entry(OffsetDateTime.class, Types.TIMESTAMP_WITH_TIMEZONE),
            Map.entry(Instant.class, Types.TIMESTAMP_WITH_TIMEZONE),
            Map.entry(UUID.class, Types.OTHER));

    /** Rendering of {@link Where.AlwaysTrue}. */
    protected abstract String alwaysTrueSql();

    /** Rendering of {@link Where.AlwaysFalse}. */
    protected abstract String alwaysFalseSql();

    @Override
    public SqlFragment select(EntityModel<?> model, Where where, OrderBy orderBy, Pagination pagination) {
        // M8-3 — collect joins from the predicate / orderBy. Empty plan ⇒ classic single-table SQL.
        Joins.Plan plan = Joins.collect(where, orderBy);
        StringBuilder sb = new StringBuilder("SELECT ");
        if (plan.isEmpty()) {
            appendColumnList(sb, model);
            sb.append(FROM).append(qualified(model));
        } else {
            appendAliasedColumnList(sb, model, Joins.ROOT_ALIAS);
            sb.append(FROM).append(qualified(model)).append(' ').append(Joins.ROOT_ALIAS);
            appendJoins(sb, plan);
        }
        appendWhere(sb, where, plan);
        appendOrderBy(sb, orderBy, plan);
        appendPagination(sb, pagination);
        return new SqlFragment(sb.toString(), List.of());
    }

    @Override
    public SqlFragment selectColumns(EntityModel<?> model, List<ProjectedColumn> columns,
                                     Where where, OrderBy orderBy, Pagination pagination) {
        Joins.Plan plan = Joins.collect(where, orderBy);
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            switch (columns.get(i)) {
                case ProjectedColumn.Leaf(var attr) -> sb.append(col(attr, plan));
                case ProjectedColumn.Expr(var sqlFragment) -> sb.append(sqlFragment);
            }
        }
        sb.append(FROM).append(qualified(model));
        if (!plan.isEmpty()) {
            sb.append(' ').append(Joins.ROOT_ALIAS);
            appendJoins(sb, plan);
        }
        appendWhere(sb, where, plan);
        appendOrderBy(sb, orderBy, plan);
        appendPagination(sb, pagination);
        return new SqlFragment(sb.toString(), List.of());
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
        return new SqlFragment(sql, List.of());
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
        return new SqlFragment(sb.toString(), List.of());
    }

    @Override
    public SqlFragment delete(EntityModel<?> model, Where where) {
        Joins.Plan plan = Joins.collect(where, OrderBy.NONE);
        if (!plan.isEmpty()) throw new IllegalStateException(
                "DELETE with joined predicate is not yet supported (path: " + plan.aliasByPath().keySet() + ")");
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(qualified(model));
        appendWhere(sb, where, plan);
        return new SqlFragment(sb.toString(), List.of());
    }

    /* ---- bind / extract ---- */

    @Override
    public int sqlType(Class<?> javaType) {
        Integer fixed = SQL_TYPES.get(javaType);
        if (fixed != null) return fixed;
        return javaType.isEnum() ? Types.VARCHAR : Types.OTHER;
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
    public <T> T extract(ResultSet rs, int idx, Class<T> javaType) throws SQLException {
        if (javaType.isEnum()) return extractEnum(rs, idx, javaType);
        if (javaType == Character.class) return extractCharacter(rs, idx);
        if (javaType == Instant.class) return extractInstant(rs, idx);
        if (javaType == String.class || javaType == BigDecimal.class) return extractReference(rs, idx, javaType);
        return extractWrapperOrObject(rs, idx, javaType);
    }

    @SuppressWarnings("unchecked")
    private static <T> T extractReference(ResultSet rs, int idx, Class<T> javaType) throws SQLException {
        return javaType == String.class ? (T) rs.getString(idx) : (T) rs.getBigDecimal(idx);
    }

    /** Boxed primitives read through the primitive getter (null on SQL NULL); anything else by type. */
    private static <T> T extractWrapperOrObject(ResultSet rs, int idx, Class<T> javaType) throws SQLException {
        if (javaType == Boolean.class) return nullable(rs.getBoolean(idx), rs);
        if (javaType == Byte.class)    return nullable(rs.getByte(idx), rs);
        if (javaType == Short.class)   return nullable(rs.getShort(idx), rs);
        if (javaType == Integer.class) return nullable(rs.getInt(idx), rs);
        if (javaType == Long.class)    return nullable(rs.getLong(idx), rs);
        if (javaType == Float.class)   return nullable(rs.getFloat(idx), rs);
        if (javaType == Double.class)  return nullable(rs.getDouble(idx), rs);
        return rs.getObject(idx, javaType);
    }

    @SuppressWarnings("unchecked")
    private static <T> T extractInstant(ResultSet rs, int idx) throws SQLException {
        OffsetDateTime odt = rs.getObject(idx, OffsetDateTime.class);
        return odt == null ? null : (T) odt.toInstant();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> T extractEnum(ResultSet rs, int idx, Class<T> javaType) throws SQLException {
        String s = rs.getString(idx);
        return s == null ? null : (T) Enum.valueOf((Class) javaType, s);
    }

    @SuppressWarnings("unchecked")
    private static <T> T extractCharacter(ResultSet rs, int idx) throws SQLException {
        String s = rs.getString(idx);
        return (s == null || s.isEmpty()) ? null : (T) Character.valueOf(s.charAt(0));
    }

    @SuppressWarnings("unchecked")
    private static <T> T nullable(Object primitive, ResultSet rs) throws SQLException {
        return rs.wasNull() ? null : (T) primitive;
    }

    /* ---- helpers ---- */

    /** The table name, schema-qualified when the model carries a schema, both quoted. */
    protected String qualified(EntityModel<?> model) {
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
            case Where.Eq(var attr)        -> sb.append(col(attr, plan)).append(" = ?");
            case Where.NotEq(var attr)     -> sb.append(col(attr, plan)).append(" <> ?");
            case Where.Lt(var attr)        -> sb.append(col(attr, plan)).append(" < ?");
            case Where.Lte(var attr)       -> sb.append(col(attr, plan)).append(" <= ?");
            case Where.Gt(var attr)        -> sb.append(col(attr, plan)).append(" > ?");
            case Where.Gte(var attr)       -> sb.append(col(attr, plan)).append(" >= ?");
            case Where.Like(var attr)      -> sb.append(col(attr, plan)).append(" LIKE ?");
            case Where.IsNull(var attr)    -> sb.append(col(attr, plan)).append(" IS NULL");
            case Where.IsNotNull(var attr) -> sb.append(col(attr, plan)).append(" IS NOT NULL");
            case Where.Between(var attr)   -> sb.append(col(attr, plan)).append(" BETWEEN ? AND ?");
            case Where.In(var attr, var arity) -> {
                sb.append(col(attr, plan)).append(IN_OPEN);
                appendPlaceholders(sb, arity, "?");
            }
            case Where.And(var children) -> renderJunction(sb, children, " AND ", plan);
            case Where.Or(var children)  -> renderJunction(sb, children, " OR ", plan);
            case Where.Not(var child) -> {
                sb.append(NOT_OPEN);
                renderPredicate(sb, child, plan);
                sb.append(')');
            }
            // M7-27 — case-insensitive comparator wrapper.
            case Where.IgnoreCase(var inner) -> renderIgnoreCase(sb, inner, plan);
            // M8-1 — unary scalar function on the column (UPPER/LOWER/LENGTH/ABS).
            case Where.Func(var fn, var inner) -> renderFunc(sb, fn, inner, plan);
            case Where.AlwaysTrue _  -> sb.append(alwaysTrueSql());
            case Where.AlwaysFalse _ -> sb.append(alwaysFalseSql());
        }
    }

    /** {@code (c1 <op> c2 <op> …)}. */
    private void renderJunction(StringBuilder sb, List<Where> children, String operator, Joins.Plan plan) {
        sb.append('(');
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(operator);
            renderPredicate(sb, children.get(i), plan);
        }
        sb.append(')');
    }

    /** {@code arity} comma-separated copies of {@code placeholder}, then the closing parenthesis. */
    private static void appendPlaceholders(StringBuilder sb, int arity, String placeholder) {
        for (int i = 0; i < arity; i++) {
            if (i > 0) sb.append(", ");
            sb.append(placeholder);
        }
        sb.append(')');
    }

    private void renderIgnoreCase(StringBuilder sb, Where inner, Joins.Plan plan) {
        switch (inner) {
            case Where.Eq(var attr)      -> appendFnLhs(sb, LOWER, col(attr, plan), " = LOWER(?)");
            case Where.NotEq(var attr)   -> appendFnLhs(sb, LOWER, col(attr, plan), " <> LOWER(?)");
            case Where.Like(var attr)    -> appendFnLhs(sb, LOWER, col(attr, plan), " LIKE LOWER(?)");
            case Where.Lt(var attr)      -> appendFnLhs(sb, LOWER, col(attr, plan), " < LOWER(?)");
            case Where.Lte(var attr)     -> appendFnLhs(sb, LOWER, col(attr, plan), " <= LOWER(?)");
            case Where.Gt(var attr)      -> appendFnLhs(sb, LOWER, col(attr, plan), " > LOWER(?)");
            case Where.Gte(var attr)     -> appendFnLhs(sb, LOWER, col(attr, plan), " >= LOWER(?)");
            case Where.Between(var attr) -> appendFnLhs(sb, LOWER, col(attr, plan), " BETWEEN LOWER(?) AND LOWER(?)");
            case Where.In(var attr, var arity) -> {
                appendFnLhs(sb, LOWER, col(attr, plan), IN_OPEN);
                appendPlaceholders(sb, arity, "LOWER(?)");
            }
            case Where.Not(var child) -> {
                sb.append(NOT_OPEN);
                renderIgnoreCase(sb, child, plan);
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
            case Where.Eq(var attr)      -> appendFnLhs(sb, sqlFn, col(attr, plan), " = ?");
            case Where.NotEq(var attr)   -> appendFnLhs(sb, sqlFn, col(attr, plan), " <> ?");
            case Where.Like(var attr)    -> appendFnLhs(sb, sqlFn, col(attr, plan), " LIKE ?");
            case Where.Lt(var attr)      -> appendFnLhs(sb, sqlFn, col(attr, plan), " < ?");
            case Where.Lte(var attr)     -> appendFnLhs(sb, sqlFn, col(attr, plan), " <= ?");
            case Where.Gt(var attr)      -> appendFnLhs(sb, sqlFn, col(attr, plan), " > ?");
            case Where.Gte(var attr)     -> appendFnLhs(sb, sqlFn, col(attr, plan), " >= ?");
            case Where.Between(var attr) -> appendFnLhs(sb, sqlFn, col(attr, plan), " BETWEEN ? AND ?");
            case Where.IsNull(var attr)    -> appendFnLhs(sb, sqlFn, col(attr, plan), " IS NULL");
            case Where.IsNotNull(var attr) -> appendFnLhs(sb, sqlFn, col(attr, plan), " IS NOT NULL");
            case Where.In(var attr, var arity) -> {
                appendFnLhs(sb, sqlFn, col(attr, plan), IN_OPEN);
                appendPlaceholders(sb, arity, "?");
            }
            case Where.Not(var child) -> {
                sb.append(NOT_OPEN);
                renderFunc(sb, fn, child, plan);
                sb.append(')');
            }
            default -> throw new IllegalArgumentException(
                    "Func wraps Eq/NotEq/Lt/Lte/Gt/Gte/Like/Between/In/IsNull/IsNotNull/Not only, got: " + inner);
        }
    }

    private static void appendFnLhs(StringBuilder sb, String sqlFn, String qualifiedColumn, String tail) {
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

    private static void appendPagination(StringBuilder sb, Pagination p) {
        switch (p) {
            case Pagination.None _ -> { /* no pagination clause */ }
            case Pagination.Offset(var offset, var limit) -> sb.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
            case Pagination.Keyset(var limit, _, _) -> sb.append(" LIMIT ").append(limit);
        }
    }
}
