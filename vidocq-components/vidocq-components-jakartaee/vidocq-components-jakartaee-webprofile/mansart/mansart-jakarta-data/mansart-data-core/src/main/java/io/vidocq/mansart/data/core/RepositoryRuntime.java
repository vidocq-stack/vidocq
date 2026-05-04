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
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(frag.sql(), Statement.RETURN_GENERATED_KEYS)) {
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
                        throw new MansartDataException("Query returned more than one result for "
                                + model.entityClass().getSimpleName());
                    }
                    return Optional.of(first);
                }
            }
        });
    }

    public <E> long countWhere(EntityModel<E> model, Where where, Object... args) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(qualifiedTable(model));
        SqlFragment selFrag = dialect.select(model, where, OrderBy.NONE, Pagination.NONE);
        // Reuse the dialect's WHERE rendering by extracting from the SELECT fragment.
        int whereIdx = selFrag.sql().indexOf(" WHERE ");
        if (whereIdx >= 0) sb.append(selFrag.sql().substring(whereIdx));
        String sql = sb.toString();
        return ConnectionScope.withConnection(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                WhereBinder.bind(dialect, ps, where, args, 1, new int[]{0});
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
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
