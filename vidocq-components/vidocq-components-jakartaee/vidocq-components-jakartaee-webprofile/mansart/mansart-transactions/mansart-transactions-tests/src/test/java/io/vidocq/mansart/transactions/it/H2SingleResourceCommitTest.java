package io.vidocq.mansart.transactions.it;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Happy path "begin → write → commit" against an embedded H2, using a single {@link Connection}
 * wrapped via {@link ConnectionXAResource} as the only enrolled resource.
 *
 * <p>This is the historical M2-IT scenario, finally enabled now that
 * {@code mansart-transactions-jdbc} provides the {@code Connection} → {@code XAResource} adapter
 * (M8). 1PC degenerate path : single resource → no prepare, just commit(onePhase=true).
 */
class H2SingleResourceCommitTest {

    @Test
    void writeCommittedIsVisibleAfterTxCloses() throws Exception {
        TransactionManager tm = new MansartTransactionManager();
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:tx-test-it;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS t (id INT)");
                s.execute("DELETE FROM t");
            }
            tm.begin();
            tm.getTransaction().enlistResource(new ConnectionXAResource(c));
            try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO t VALUES (1)");
            }
            tm.commit();

            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        }
    }
}
