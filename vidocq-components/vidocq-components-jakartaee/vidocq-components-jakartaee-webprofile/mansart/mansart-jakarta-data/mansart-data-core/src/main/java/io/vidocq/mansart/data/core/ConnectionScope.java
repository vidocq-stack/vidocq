package io.vidocq.mansart.data.core;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Propagates a {@link Connection} to the inner action via a {@link ScopedValue}, so that nested
 * runtime calls reuse the same connection (transaction context) without resorting to
 * {@code ThreadLocal}.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #withConnection} — opens a connection in auto-commit mode for the action's lifetime.
 *       If a connection is already bound by an enclosing scope, it is reused. This is the path the
 *       generated repository implementations take for every CRUD / JDQL call.</li>
 *   <li>{@link #inTransaction} — opens a connection in manual-commit mode, binds it to the same
 *       {@link ScopedValue}, runs the body, and commits on success or rolls back on any thrown
 *       exception. Inside the body, every nested {@link #withConnection} hits the bound connection
 *       and therefore lands in the same transaction. Repository providers (mansart-pool,
 *       HikariCP, JNDI…) only need to expose a {@link DataSource} — the transaction orchestration
 *       lives entirely here.</li>
 * </ul>
 *
 * <p>{@link #inTransaction} is virtual-thread-safe: {@link ScopedValue} propagates to child
 * {@link StructuredTaskScope} forks, but explicit thread-creation outside such a scope does NOT
 * inherit the bound connection — a deliberate choice that makes the transaction boundary explicit
 * and prevents accidental connection sharing across unrelated work.
 */
public final class ConnectionScope {

    static final ScopedValue<Connection> CURRENT = ScopedValue.newInstance();

    private ConnectionScope() {}

    @FunctionalInterface
    public interface SqlAction<T> {
        T run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface TxAction<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface TxRunnable {
        void run() throws Exception;
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

    /**
     * Runs {@code body} inside a JDBC transaction. Acquires a connection, switches it to
     * manual-commit mode, binds it to {@link #CURRENT}, executes the body, then commits on
     * normal return or rolls back if anything is thrown — including {@link Error}s, which are
     * re-thrown after rollback so the caller sees them unchanged.
     *
     * <p>Nested {@code inTransaction} calls join the enclosing transaction (no commit at the
     * inner exit; the outer one decides). This matches the JDBC notion of a single physical
     * connection per transaction and avoids hidden savepoint logic.
     *
     * @return the value returned by {@code body}
     * @throws MansartDataException wraps any SQL error from acquire / setAutoCommit / commit /
     *         rollback, and any non-runtime checked exception thrown by the body
     */
    public static <T> T inTransaction(DataSource dataSource, TxAction<T> body) {
        if (CURRENT.isBound()) {
            // Already in a transaction — join it, do not open a new physical connection.
            return runJoined(body);
        }
        Connection c;
        try {
            c = dataSource.getConnection();
        } catch (SQLException e) {
            throw new MansartDataException("Failed to acquire connection for transaction", e);
        }
        try {
            c.setAutoCommit(false);
        } catch (SQLException e) {
            closeQuietly(c);
            throw new MansartDataException("Failed to switch connection to manual-commit", e);
        }
        boolean committed = false;
        try {
            T result = ScopedValue.where(CURRENT, c).call(body::run);
            c.commit();
            committed = true;
            return result;
        } catch (Exception e) {
            rollbackQuietly(c);
            if (e instanceof RuntimeException re) throw re;
            throw new MansartDataException("Transaction body failed", e);
        } catch (Error e) {
            rollbackQuietly(c);
            throw e;
        } finally {
            // Best-effort restore + return-to-pool. If we never committed, we already rolled back
            // above; setAutoCommit(true) is the standard contract before handing the connection
            // back to a pool that expects auto-commit by default.
            try { c.setAutoCommit(true); } catch (SQLException ignored) {}
            closeQuietly(c);
            if (!committed) {
                // already rolled back; no-op marker
            }
        }
    }

    /** Convenience overload for the common no-return case. */
    public static void inTransaction(DataSource dataSource, TxRunnable body) {
        inTransaction(dataSource, () -> { body.run(); return null; });
    }

    private static <T> T runJoined(TxAction<T> body) {
        try {
            return body.run();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new MansartDataException("Joined transaction body failed", e);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) {}
    }

    private static void closeQuietly(Connection c) {
        try { c.close(); } catch (SQLException ignored) {}
    }
}
