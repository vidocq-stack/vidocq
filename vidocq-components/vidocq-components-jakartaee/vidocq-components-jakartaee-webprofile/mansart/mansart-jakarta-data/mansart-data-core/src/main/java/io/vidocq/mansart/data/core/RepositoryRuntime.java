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
