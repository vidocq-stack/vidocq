package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.OrderBy;
import io.vidocq.mansart.data.dialect.Pagination;
import io.vidocq.mansart.data.dialect.SqlFragment;
import io.vidocq.mansart.data.dialect.Where;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import jakarta.data.Order;
import jakarta.data.Sort;
import jakarta.data.exceptions.OptimisticLockingFailureException;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stateless runtime shared by every generated {@code *RepositoryImpl}. Each method is invoked
 * once per repository call and runs on the calling virtual thread.
 *
 * <p>M3a scope: {@link #save(EntityModel, Object)}, {@link #findById(EntityModel, Object)},
 * {@link #findAll(EntityModel)}, {@link #deleteById(EntityModel, Object)},
 * {@link #count(EntityModel)}, {@link #existsById(EntityModel, Object)}.
 * Pagination, derived queries, JDQL come in M3b/M5.
 */
public final class RepositoryRuntime {

    private final DataSource dataSource;
    private final Dialect    dialect;

    public RepositoryRuntime(DataSource dataSource, Dialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    public Dialect dialect() { return dialect; }

    /* -------- M7-7 schema bootstrap ---------- */

    /**
     * Emits {@code CREATE TABLE IF NOT EXISTS} for the given entity model, mapping each Mansart
     * attribute to a portable JDBC type accepted by H2 and PostgreSQL. Used by the runtime
     * fallback path (M7) so deployments that skip APT (e.g. the official Jakarta Data TCK jar)
     * still get a usable schema. The compile-time path is unchanged — user code keeps owning
     * its schema there.
     *
     * <p>Idempotent. Failures are wrapped as {@link MansartDataException}.
     */
    public <E> void ensureTable(EntityModel<E> model) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(qualifiedTable(model)).append(" (");
        boolean first = true;
        for (Attribute<E, ?> a : model.attributes()) {
            if (!first) sb.append(", ");
            sb.append('"').append(a.columnName()).append("\" ")
              .append(sqlDdlType(a.javaType()));
            if (a == model.id()) sb.append(" PRIMARY KEY");
            first = false;
        }
        sb.append(')');
        String sql = sb.toString();
        ConnectionScope.withConnection(dataSource, c -> {
            try (Statement s = c.createStatement()) { s.execute(sql); return null; }
        });
    }

    private static String sqlDdlType(Class<?> t) {
        if (t == Long.class || t == long.class)         return "BIGINT";
        if (t == Integer.class || t == int.class)       return "INTEGER";
        if (t == Short.class || t == short.class)       return "SMALLINT";
        if (t == Byte.class || t == byte.class)         return "SMALLINT";
        if (t == Boolean.class || t == boolean.class)   return "BOOLEAN";
        if (t == Double.class || t == double.class)     return "DOUBLE PRECISION";
        if (t == Float.class || t == float.class)       return "REAL";
        if (t == java.math.BigDecimal.class
                || t == java.math.BigInteger.class)     return "NUMERIC(38, 10)";
        if (t == java.time.LocalDate.class)             return "DATE";
        if (t == java.time.LocalTime.class)             return "TIME";
        if (t == java.time.LocalDateTime.class)         return "TIMESTAMP";
        if (t == java.time.OffsetDateTime.class
                || t == java.time.Instant.class)        return "TIMESTAMP WITH TIME ZONE";
        if (t == java.util.UUID.class)                  return "UUID";
        if (t.isEnum())                                 return "VARCHAR(255)";
        return "VARCHAR(255)";
    }

    /* -------- CRUD ---------- */

    @SuppressWarnings("unchecked")
    public <E> E save(EntityModel<E> model, E entity) {
        Object idValue = readId(model, entity);
        if (idValue == null) {
            return insert(model, entity);
        }
        // Existing id → upsert via dialect-specific MERGE.
        SqlFragment frag = dialect.merge(model);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                bindAllAttributes(ps, model, entity);
                ps.executeUpdate();
                return entity;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <E> E insert(EntityModel<E> model, E entity) {
        SqlFragment frag = dialect.insert(model, true);
        // Use the explicit-column form so PostgreSQL returns only the id column (PG with the
        // RETURN_GENERATED_KEYS flag returns RETURNING * which would expose every column).
        String[] keyColumns = new String[]{ model.id().columnName() };
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql(), keyColumns)) {
                bindWritableAttributes(ps, model, entity);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        IdAttribute<E, Object> id = (IdAttribute<E, Object>) model.id();
                        Object generated = dialect.extract(keys, 1, id.javaType());
                        try { id.setter().invoke(entity, generated); }
                        catch (Throwable t) { throw new MansartDataException("Failed to set generated id", t); }
                    }
                }
                return entity;
            } catch (SQLException e) {
                throw dialect.translate(e);
            }
        });
    }

    public <E, K> Optional<E> findById(EntityModel<E> model, K id) {
        Where where = Where.eq(model.id());
        SqlFragment frag = dialect.select(model, where, OrderBy.NONE, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                dialect.bind(ps, 1, id, model.id().javaType());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(RowMapper.map(model, dialect, rs));
                    return Optional.empty();
                }
            }
        });
    }

    public <E> List<E> findAll(EntityModel<E> model) {
        SqlFragment frag = dialect.select(model, Where.ALWAYS_TRUE, OrderBy.NONE, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql());
                 ResultSet rs = ps.executeQuery()) {
                List<E> result = new ArrayList<>();
                while (rs.next()) result.add(RowMapper.map(model, dialect, rs));
                return result;
            }
        });
    }

    public <E, K> boolean deleteById(EntityModel<E> model, K id) {
        SqlFragment frag = dialect.delete(model, Where.eq(model.id()));
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                dialect.bind(ps, 1, id, model.id().javaType());
                return ps.executeUpdate() > 0;
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <E> boolean delete(EntityModel<E> model, E entity) {
        Object id = readId(model, entity);
        if (id == null) return false;
        return deleteById(model, id);
    }

    public <E> long count(EntityModel<E> model) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable(model);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    public <E, K> boolean existsById(EntityModel<E> model, K id) {
        return findById(model, id).isPresent();
    }

    /* -------- derived queries (M3b) ---------- */

    public <E> List<E> queryList(EntityModel<E> model, Where where, OrderBy orderBy, Object... args) {
        SqlFragment frag = dialect.select(model, where, orderBy, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    List<E> out = new ArrayList<>();
                    while (rs.next()) out.add(RowMapper.map(model, dialect, rs));
                    return out;
                }
            }
        });
    }

    public <E> Optional<E> queryOne(EntityModel<E> model, Where where, Object... args) {
        SqlFragment frag = dialect.select(model, where, OrderBy.NONE, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<E>empty();
                    E first = RowMapper.map(model, dialect, rs);
                    if (rs.next()) {
                        throw new jakarta.data.exceptions.NonUniqueResultException(
                                "Query returned more than one result for "
                                + model.entityClass().getSimpleName());
                    }
                    return Optional.of(first);
                }
            }
        });
    }

    public <E> long countWhere(EntityModel<E> model, Where where, Object... args) {
        // M8-3 — route through dialect.selectColumns so any path-based predicate gets its joins
        // and aliasing applied uniformly. Project COUNT(*) as a literal expression entry.
        SqlFragment frag = dialect.selectColumns(model,
                java.util.List.of(new io.vidocq.mansart.data.dialect.Dialect.ProjectedColumn.Expr("COUNT(*)")),
                where, OrderBy.NONE, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /* -------- aggregates + projections (M5-4) ---------- */

    /**
     * Runs {@code SELECT <op>("col") FROM table [WHERE …]} and extracts a single value of type
     * {@code resultType}. Used by JDQL aggregates (SUM/AVG/MIN/MAX). Returns {@code null} if SQL
     * returns NULL (e.g. SUM over an empty set).
     */
    public <E, T> T aggregate(EntityModel<E> model, String op, Attribute<?, ?> attr,
                              Class<T> resultType, Where where, Object... args) {
        // M8-3 — go through dialect.selectColumns so joined predicates lower correctly. The
        // aggregate column itself stays unjoined (M8-1 scope: aggregate over a flat attribute).
        String aggExpr = op + "(\"" + attr.columnName() + "\")";
        return scalarSelect(model, new io.vidocq.mansart.data.dialect.Dialect.ProjectedColumn.Expr(aggExpr),
                resultType, where, args);
    }

    /** Returns the projected column values that match {@code where}, ordered by {@code orderBy}. */
    public <E, T> java.util.List<T> projectColumn(EntityModel<E> model, Attribute<?, ?> attr,
                                                  Class<T> resultType, Where where, OrderBy orderBy,
                                                  Object... args) {
        SqlFragment frag = dialect.selectColumns(model,
                java.util.List.of(new io.vidocq.mansart.data.dialect.Dialect.ProjectedColumn.Leaf(attr)),
                where, orderBy, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    java.util.List<T> out = new ArrayList<>();
                    while (rs.next()) out.add(dialect.extract(rs, 1, resultType));
                    return out;
                }
            }
        });
    }

    /**
     * M8-2 — multi-column projection. Returns one {@code Object[]} per row, each array's length
     * equal to {@code attrs.size()}. Components are extracted using the dialect's {@code extract}
     * keyed by each attribute's declared Java type. M8-3 — supports {@link io.vidocq.mansart.data.dialect.attribute.JoinedAttribute}
     * leaves through the dialect's {@code selectColumns} (joins materialised in the FROM clause).
     */
    public <E> java.util.List<Object[]> projectColumns(EntityModel<E> model,
                                                       java.util.List<Attribute<?, ?>> attrs,
                                                       Where where, OrderBy orderBy, Object... args) {
        java.util.List<io.vidocq.mansart.data.dialect.Dialect.ProjectedColumn> cols =
                new ArrayList<>(attrs.size());
        for (Attribute<?, ?> a : attrs) {
            cols.add(new io.vidocq.mansart.data.dialect.Dialect.ProjectedColumn.Leaf(a));
        }
        SqlFragment frag = dialect.selectColumns(model, cols, where, orderBy, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    java.util.List<Object[]> out = new ArrayList<>();
                    while (rs.next()) {
                        Object[] row = new Object[attrs.size()];
                        for (int i = 0; i < attrs.size(); i++) {
                            row[i] = dialect.extract(rs, i + 1, attrs.get(i).javaType());
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    private <E, T> T scalarSelect(EntityModel<E> model,
                                  io.vidocq.mansart.data.dialect.Dialect.ProjectedColumn col,
                                  Class<T> resultType, Where where, Object[] args) {
        SqlFragment frag = dialect.selectColumns(model, java.util.List.of(col), where, OrderBy.NONE, Pagination.NONE);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? dialect.extract(rs, 1, resultType) : null;
                }
            }
        });
    }

    /** Extracts {@code WHERE …} (and optional ORDER BY) from a dialect-rendered SELECT, reused
     *  to compose custom-projection SQL without re-implementing the predicate renderer here. */
    private String whereOrderTail(EntityModel<?> model, Where where, OrderBy orderBy) {
        SqlFragment selFrag = dialect.select(model, where, orderBy, Pagination.NONE);
        String src = selFrag.sql();
        int whereIdx = src.indexOf(" WHERE ");
        int orderIdx = src.indexOf(" ORDER BY ");
        int start = -1;
        if (whereIdx >= 0 && (orderIdx < 0 || whereIdx < orderIdx)) start = whereIdx;
        else if (orderIdx >= 0) start = orderIdx;
        return start >= 0 ? src.substring(start) : "";
    }

    public <E> boolean existsWhere(EntityModel<E> model, Where where, Object... args) {
        return countWhere(model, where, args) > 0;
    }

    /* -------- pagination (M3c) ---------- */

    public <E> Page<E> queryPage(EntityModel<E> model, Where where, OrderBy orderBy,
                                 PageRequest pageRequest, Object... args) {
        Pagination.Offset pag = new Pagination.Offset(
                (pageRequest.page() - 1) * pageRequest.size(),
                pageRequest.size());
        SqlFragment frag = dialect.select(model, where, orderBy, pag);
        List<E> content = ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    List<E> out = new ArrayList<>();
                    while (rs.next()) out.add(RowMapper.map(model, dialect, rs));
                    return out;
                }
            }
        });
        long total = pageRequest.requestTotal() ? countWhere(model, where, args) : -1L;
        return new MansartPage<>(content, pageRequest, total);
    }

    /**
     * Cursor-based ("keyset") pagination with full multi-attribute lexicographic comparison
     * (M3c-3). The cursor is the tuple of OrderBy attribute values from a previously seen row;
     * the next page is the rows where {@code (a, b, c …) > (a0, b0, c0 …)} (or {@code <} for
     * {@code CURSOR_PREVIOUS}). The lexicographic predicate is encoded as
     * {@code (a > a0) OR (a = a0 AND b > b0) OR (a = a0 AND b = b0 AND c > c0)} so that any
     * dialect with simple boolean operators can render it without a row-constructor extension.
     */
    public <E> jakarta.data.page.CursoredPage<E> queryCursored(
            EntityModel<E> model, Where userWhere, OrderBy orderBy, PageRequest pr, Object... args) {
        if (orderBy.isEmpty()) {
            // Default cursor key = id ascending so callers don't have to thread an OrderBy
            // through every Page-returning method (Jakarta Data permits this).
            orderBy = new OrderBy(java.util.List.of(OrderBy.Order.asc(model.id())));
        }
        int n = orderBy.orders().size();

        Where combined = userWhere;
        Object[] effectiveArgs = args;
        OrderBy effectiveOrder = orderBy;

        if (pr.cursor().isPresent()) {
            PageRequest.Cursor cursor = pr.cursor().get();
            if (cursor.size() != n) {
                throw new MansartDataException("Cursor element count must match OrderBy size (got "
                        + cursor.size() + ", expected " + n + ")");
            }
            boolean isAfter = pr.mode() == PageRequest.Mode.CURSOR_NEXT;

            // Lexicographic OR-AND chain.
            List<Where> orParts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<Where> andParts = new ArrayList<>(i + 1);
                for (int j = 0; j < i; j++) {
                    andParts.add(Where.eq(orderBy.orders().get(j).attr()));
                }
                OrderBy.Order o = orderBy.orders().get(i);
                boolean asc = o.direction() == OrderBy.Order.Direction.ASC;
                boolean useGt = (asc == isAfter);
                andParts.add(useGt ? new Where.Gt(o.attr()) : new Where.Lt(o.attr()));
                orParts.add(andParts.size() == 1 ? andParts.get(0)
                        : Where.and(andParts.toArray(new Where[0])));
            }
            Where cursorPred = orParts.size() == 1 ? orParts.get(0)
                    : Where.or(orParts.toArray(new Where[0]));
            combined = (userWhere instanceof Where.AlwaysTrue)
                    ? cursorPred
                    : Where.and(userWhere, cursorPred);

            // For CURSOR_PREVIOUS, flip every direction so the page is built backward.
            if (pr.mode() == PageRequest.Mode.CURSOR_PREVIOUS) {
                List<OrderBy.Order> reversed = new ArrayList<>(n);
                for (OrderBy.Order o : orderBy.orders()) {
                    boolean asc = o.direction() == OrderBy.Order.Direction.ASC;
                    reversed.add(asc ? OrderBy.Order.desc(o.attr()) : OrderBy.Order.asc(o.attr()));
                }
                effectiveOrder = new OrderBy(reversed);
            }

            // Expand args. For each OR clause i, append cursor[0..i-1] (for ==) then cursor[i] (for >).
            int extraBindings = n * (n + 1) / 2;
            Object[] expanded = new Object[args.length + extraBindings];
            System.arraycopy(args, 0, expanded, 0, args.length);
            int idx = args.length;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < i; j++) expanded[idx++] = cursor.get(j);
                expanded[idx++] = cursor.get(i);
            }
            effectiveArgs = expanded;
        }

        Pagination.Offset pag = new Pagination.Offset(0, pr.size());
        SqlFragment frag = dialect.select(model, combined, effectiveOrder, pag);

        Where finalWhere = combined;
        Object[] finalArgs = effectiveArgs;

        List<E> queryResult = ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, finalWhere, finalArgs, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    List<E> out = new ArrayList<>();
                    while (rs.next()) out.add(RowMapper.map(model, dialect, rs));
                    return out;
                }
            }
        });

        if (pr.mode() == PageRequest.Mode.CURSOR_PREVIOUS) {
            java.util.Collections.reverse(queryResult);
        }

        // Capture the cursor tuple for each materialized row.
        List<List<Object>> cursors = new ArrayList<>(queryResult.size());
        for (E e : queryResult) {
            List<Object> values = new ArrayList<>(n);
            for (OrderBy.Order o : orderBy.orders()) {
                @SuppressWarnings("unchecked")
                Attribute<E, Object> a = (Attribute<E, Object>) o.attr();
                try { values.add(a.getter().invoke(e)); }
                catch (Throwable t) { throw new MansartDataException("Failed to read cursor attribute", t); }
            }
            cursors.add(values);
        }

        long total = pr.requestTotal() ? countWhere(model, userWhere, args) : -1L;
        return new MansartCursoredPage<>(queryResult, pr, total, cursors);
    }

    /** Maps a {@code jakarta.data.Order<E>} to the dialect-neutral {@link OrderBy}. */
    public <E> OrderBy toOrderBy(EntityModel<E> model, Order<E> order) {
        if (order == null) return OrderBy.NONE;
        List<OrderBy.Order> orders = new ArrayList<>();
        for (Sort<? super E> sort : order.sorts()) {
            Attribute<E, ?> attr = model.attribute(sort.property())
                    .orElseThrow(() -> new MansartDataException(
                            "Unknown attribute '" + sort.property() + "' on " + model.entityClass().getSimpleName()));
            orders.add(sort.isAscending() ? OrderBy.Order.asc(attr) : OrderBy.Order.desc(attr));
        }
        return orders.isEmpty() ? OrderBy.NONE : new OrderBy(orders);
    }

    public <E> long deleteWhere(EntityModel<E> model, Where where, Object... args) {
        SqlFragment frag = dialect.delete(model, where);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                return (long) ps.executeUpdate();
            }
        });
    }

    /**
     * Targeted UPDATE for JDQL {@code UPDATE … SET col = ?} statements (M5-2).
     *
     * <p>Args layout: SET values come first (in {@code attributes} order), then any args consumed
     * by {@code where} (in render order). Returns the number of rows affected.
     */
    /**
     * M7-24 — generic UPDATE for JDQL queries with arithmetic SET expressions
     * (e.g. {@code SET length = length + ?1}). The caller supplies the fully-rendered
     * {@code SET …} clause string (without the leading "SET ") together with the bindings
     * those expressions consume; the WHERE bindings follow as usual.
     */
    public <E> long executeUpdateRaw(EntityModel<E> model, String setClause,
                                     java.util.List<Object> setArgs,
                                     java.util.List<Class<?>> setArgTypes,
                                     Where where, Object... whereArgs) {
        StringBuilder sb = new StringBuilder("UPDATE ").append(qualifiedTable(model))
                .append(" SET ").append(setClause);
        SqlFragment selFrag = dialect.select(model, where, OrderBy.NONE, Pagination.NONE);
        int whereIdx = selFrag.sql().indexOf(" WHERE ");
        if (whereIdx >= 0) sb.append(selFrag.sql().substring(whereIdx));
        String sql = sb.toString();
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int idx = 1;
                for (int i = 0; i < setArgs.size(); i++) {
                    dialect.bind(ps, idx++, setArgs.get(i), setArgTypes.get(i));
                }
                WhereBinder.bind(dialect, ps, where, whereArgs == null ? new Object[0] : whereArgs,
                        idx, new int[]{0});
                return (long) ps.executeUpdate();
            }
        });
    }

    public <E> long executeUpdate(EntityModel<E> model, java.util.List<Attribute<?, ?>> attributes,
                                  Where where, Object... args) {
        SqlFragment frag = dialect.updateSet(model, attributes, where);
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                int idx = 1;
                for (int i = 0; i < attributes.size(); i++) {
                    dialect.bind(ps, idx++, args[i], attributes.get(i).javaType());
                }
                Object[] whereArgs;
                if (args.length > attributes.size()) {
                    whereArgs = new Object[args.length - attributes.size()];
                    System.arraycopy(args, attributes.size(), whereArgs, 0, whereArgs.length);
                } else {
                    whereArgs = new Object[0];
                }
                WhereBinder.bind(dialect, ps, where, whereArgs, idx, new int[]{0});
                return (long) ps.executeUpdate();
            }
        });
    }

    /* -------- lifecycle (M3b-2): @Insert, @Update, @Delete ---------- */

    /** Strict insert (never an upsert). Translates SQLState 23505 → {@link jakarta.data.exceptions.EntityExistsException}. */
    public <E> E insertStrict(EntityModel<E> model, E entity) {
        return insert(model, entity);
    }

    /**
     * Strict update by id (and version, if {@code @Version} is present). Increments the version
     * field on success; throws {@link OptimisticLockingFailureException} if no row matched.
     */
    @SuppressWarnings("unchecked")
    public <E> E updateStrict(EntityModel<E> model, E entity) {
        Object id = readId(model, entity);
        if (id == null) {
            throw new OptimisticLockingFailureException("Cannot update entity without id");
        }

        Where where;
        Object oldVersion = null;
        if (model.version().isPresent()) {
            VersionAttribute<E, ?> ver = (VersionAttribute<E, ?>) model.version().get();
            oldVersion = readAttribute(ver, entity);
            if (oldVersion == null) oldVersion = zero(ver.javaType());
            Object newVersion = increment(oldVersion);
            try { ((Attribute<E, Object>) ver).setter().invoke(entity, newVersion); }
            catch (Throwable t) { throw new MansartDataException("Failed to bump @Version on " + model.entityClass(), t); }
            where = Where.and(Where.eq(model.id()), Where.eq(ver));
        } else {
            where = Where.eq(model.id());
        }

        SqlFragment frag = dialect.update(model, where);
        Object versionForBind = oldVersion;
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                int idx = 1;
                for (Attribute<E, ?> a : model.attributes()) {
                    if (a == model.id()) continue;
                    Object v = readAttribute(a, entity);
                    dialect.bind(ps, idx++, valueToBind(a, v), bindType(a));
                }
                dialect.bind(ps, idx++, id, model.id().javaType());
                if (model.version().isPresent()) {
                    VersionAttribute<E, ?> ver = (VersionAttribute<E, ?>) model.version().get();
                    dialect.bind(ps, idx++, versionForBind, ver.javaType());
                }
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    if (model.version().isPresent()) {
                        try {
                            ((Attribute<E, Object>) model.version().get()).setter().invoke(entity, versionForBind);
                        } catch (Throwable ignored) {}
                    }
                    throw new OptimisticLockingFailureException(
                            "No row matched id=" + id
                            + (model.version().isPresent() ? " and version=" + versionForBind : "")
                            + " for " + model.entityClass().getSimpleName());
                }
                return entity;
            }
        });
    }

    /**
     * Strict delete by id (and version, if {@code @Version} is present). Throws
     * {@link OptimisticLockingFailureException} if no row matched.
     */
    @SuppressWarnings("unchecked")
    public <E> void deleteStrict(EntityModel<E> model, E entity) {
        Object id = readId(model, entity);
        if (id == null) {
            throw new OptimisticLockingFailureException("Cannot delete entity without id");
        }

        Where where;
        Object versionForBind = null;
        if (model.version().isPresent()) {
            VersionAttribute<E, ?> ver = (VersionAttribute<E, ?>) model.version().get();
            versionForBind = readAttribute(ver, entity);
            where = Where.and(Where.eq(model.id()), Where.eq(ver));
        } else {
            where = Where.eq(model.id());
        }
        SqlFragment frag = dialect.delete(model, where);
        Object versionToBind = versionForBind;
        int affected = ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql())) {
                int idx = 1;
                dialect.bind(ps, idx++, id, model.id().javaType());
                if (model.version().isPresent()) {
                    VersionAttribute<E, ?> ver = (VersionAttribute<E, ?>) model.version().get();
                    dialect.bind(ps, idx++, versionToBind, ver.javaType());
                }
                return ps.executeUpdate();
            }
        });
        if (affected == 0) {
            throw new OptimisticLockingFailureException(
                    "No row matched id=" + id
                    + (model.version().isPresent() ? " and version=" + versionForBind : "")
                    + " for " + model.entityClass().getSimpleName());
        }
    }

    private static Object increment(Object v) {
        return switch (v) {
            case Integer i -> i + 1;
            case Long l    -> l + 1L;
            case Short s   -> (short) (s + 1);
            case null      -> 1;
            default        -> throw new MansartDataException("Unsupported @Version type: " + v.getClass());
        };
    }

    private static Object zero(Class<?> type) {
        if (type == Integer.class) return 0;
        if (type == Long.class)    return 0L;
        if (type == Short.class)   return (short) 0;
        throw new MansartDataException("Unsupported @Version type: " + type);
    }

    /* -------- helpers ---------- */

    @SuppressWarnings("unchecked")
    private <E> Object readId(EntityModel<E> model, E entity) {
        try {
            return ((IdAttribute<E, Object>) model.id()).getter().invoke(entity);
        } catch (Throwable t) {
            throw new MansartDataException("Failed to read id of " + model.entityClass(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private <E> void bindAllAttributes(PreparedStatement ps, EntityModel<E> model, E entity) throws SQLException {
        int idx = 1;
        for (Attribute<E, ?> a : model.attributes()) {
            Object v = readAttribute(a, entity);
            dialect.bind(ps, idx++, valueToBind(a, v), bindType(a));
        }
    }

    @SuppressWarnings("unchecked")
    private <E> void bindWritableAttributes(PreparedStatement ps, EntityModel<E> model, E entity) throws SQLException {
        int idx = 1;
        for (Attribute<E, ?> a : model.attributes()) {
            if (a == model.id() && model.id().generated()) continue;
            Object v = readAttribute(a, entity);
            dialect.bind(ps, idx++, valueToBind(a, v), bindType(a));
        }
    }

    @SuppressWarnings("unchecked")
    private <E> Object readAttribute(Attribute<E, ?> a, E entity) {
        try {
            return a.getter().invoke(entity);
        } catch (Throwable t) {
            throw new MansartDataException("Failed to read attribute " + a.name(), t);
        }
    }

    private Object valueToBind(Attribute<?, ?> a, Object value) {
        if (a instanceof ReferenceAttribute<?, ?> ref && value != null) {
            // Bind the FK id of the referenced entity. M3a assumes the referenced entity has a
            // public no-arg ctor and an `id` field accessible via its own _Entity model — that
            // requires inter-entity model lookup which we defer. For M3a, callers must set the
            // FK only on entities whose references already have their id populated; we just call
            // the JDBC driver with the referenced object directly and let the driver fail loudly
            // if the test exercises it. References are not exercised in M3a tests.
            throw new MansartDataException("Reference attribute binding lands in M3b. Use only "
                    + "scalar attributes for now (attribute: " + a.name() + ").");
        }
        return value;
    }

    private Class<?> bindType(Attribute<?, ?> a) {
        if (a instanceof ReferenceAttribute<?, ?>) return Long.class; // FK fallback type — M3b refines.
        return a.javaType();
    }

    private String qualifiedTable(EntityModel<?> model) {
        return (model.schema() == null || model.schema().isEmpty())
                ? "\"" + model.tableName() + "\""
                : "\"" + model.schema() + "\".\"" + model.tableName() + "\"";
    }
}
