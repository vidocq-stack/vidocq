package io.vidocq.mansart.data.core;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Propagates a {@link Connection} to the inner action via a {@link ScopedValue}, so that nested
 * runtime calls reuse the same connection (transaction context) without resorting to
 * {@code ThreadLocal}.
 *
 * <p>If a connection is already bound (e.g. a surrounding transaction scope), the action is run on
 * it. Otherwise a fresh connection is acquired from the {@link DataSource}, bound, used, and
 * released — auto-commit, no transaction.
 */
public final class ConnectionScope {

    static final ScopedValue<Connection> CURRENT = ScopedValue.newInstance();

    private ConnectionScope() {}

    @FunctionalInterface
    public interface SqlAction<T> {
        T run(Connection c) throws SQLException;
    }

    public static <T> T withConnection(DataSource dataSource, SqlAction<T> action) {
        if (CURRENT.isBound()) {
            try {
                return action.run(CURRENT.get());
            } catch (SQLException e) {
                throw new MansartDataException("SQL error", e);
            }
        }
        try (Connection c = dataSource.getConnection()) {
            return ScopedValue.where(CURRENT, c).call(() -> action.run(c));
        } catch (SQLException e) {
            throw new MansartDataException("SQL error", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new MansartDataException("Unexpected error", e);
        }
    }
}
