package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Materializes one entity instance from the current row of a {@link ResultSet}.
 *
 * <p>Columns are read in the same order as {@link EntityModel#attributes()} — the dialect produces
 * SQL with that exact column ordering, so the mapper just iterates and binds.
 *
 * <p>Reference attributes (M3a) are skipped: their FK column is read into a stub instance with the
 * id field set, but no lazy proxy is installed and no eager fetch is performed. Full handling lands
 * in M3b together with relation traversal.
 */
final class RowMapper {

    private RowMapper() {}

    @SuppressWarnings("unchecked")
    static <E> E map(EntityModel<E> model, Dialect dialect, ResultSet rs) throws SQLException {
        E entity;
        try {
            entity = (E) model.constructor().invoke();
        } catch (Throwable t) {
            throw new MansartDataException("Failed to instantiate " + model.entityClass(), t);
        }
        int idx = 1;
        for (Attribute<E, ?> a : model.attributes()) {
            Object value = dialect.extract(rs, idx++, a.javaType());
            if (value == null) continue;
            if (a instanceof ReferenceAttribute<?, ?>) {
                // M3a skip — the FK id is in `value` but we do not materialize a stub yet.
                continue;
            }
            try {
                ((Attribute<E, Object>) a).setter().invoke(entity, value);
            } catch (Throwable t) {
                throw new MansartDataException("Failed to set " + a.name() + " on " + model.entityClass(), t);
            }
        }
        return entity;
    }
}
