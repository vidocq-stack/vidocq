package io.vidocq.mansart.transactions.jdbc;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end JDBC tests against H2 in-memory : drives a {@link ConnectionXAResource} through a
 * {@link MansartTransactionManager} and verifies that COMMIT and ROLLBACK propagate to the DB.
 */
class ConnectionXAResourceTest {

    private TransactionManager tm;
    private Connection conn;
    private String dbName;

    @BeforeEach
    void setUp() throws SQLException {
        tm = new MansartTransactionManager();
        // Unique DB per test — DB_CLOSE_DELAY=-1 keeps it alive across connect/disconnect cycles.
        dbName = "tx-" + UUID.randomUUID();
        conn = DriverManager.getConnection("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var s = conn.createStatement()) {
            s.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT)");
            s.execute("INSERT INTO accounts VALUES (1, 100)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) tm.rollback();
        if (conn != null && !conn.isClosed()) conn.close();
    }

    @Test
    void commitPersistsChanges() throws Exception {
        tm.begin();
        tm.getTransaction().enlistResource(new ConnectionXAResource(conn));
        try (var s = conn.prepareStatement("UPDATE accounts SET balance = ? WHERE id = 1")) {
            s.setInt(1, 200);
            s.executeUpdate();
        }
        tm.commit();

        assertThat(balance()).isEqualTo(200);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void rollbackDiscardsChanges() throws Exception {
        tm.begin();
        tm.getTransaction().enlistResource(new ConnectionXAResource(conn));
        try (var s = conn.prepareStatement("UPDATE accounts SET balance = ? WHERE id = 1")) {
            s.setInt(1, 999);
            s.executeUpdate();
        }
        tm.rollback();

        assertThat(balance()).isEqualTo(100);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void autoCommitRestoredAfterCommit() throws Exception {
        assertThat(conn.getAutoCommit()).isTrue();

        tm.begin();
        tm.getTransaction().enlistResource(new ConnectionXAResource(conn));
        // Inside the TX : autoCommit was switched off by start().
        assertThat(conn.getAutoCommit()).isFalse();
        tm.commit();

        // After commit : autoCommit restored.
        assertThat(conn.getAutoCommit()).isTrue();
    }

    @Test
    void autoCommitRestoredAfterRollback() throws Exception {
        tm.begin();
        tm.getTransaction().enlistResource(new ConnectionXAResource(conn));
        tm.rollback();
        assertThat(conn.getAutoCommit()).isTrue();
    }

    @Test
    void preExistingAutoCommitOffIsHonoured() throws Exception {
        // Caller already manages autoCommit themselves — start() must not toggle it back on after.
        conn.setAutoCommit(false);

        tm.begin();
        tm.getTransaction().enlistResource(new ConnectionXAResource(conn));
        try (var s = conn.prepareStatement("UPDATE accounts SET balance = 250 WHERE id = 1")) {
            s.executeUpdate();
        }
        tm.commit();

        assertThat(conn.getAutoCommit()).isFalse();   // unchanged
        assertThat(balance()).isEqualTo(250);
    }

    private int balance() throws SQLException {
        try (var s = conn.createStatement();
             var rs = s.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
