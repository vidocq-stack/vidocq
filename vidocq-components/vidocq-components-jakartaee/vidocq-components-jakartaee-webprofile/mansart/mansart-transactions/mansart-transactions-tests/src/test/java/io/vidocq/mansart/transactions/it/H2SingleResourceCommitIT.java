package io.vidocq.mansart.transactions.it;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-IT — happy path "begin → write → commit" against an embedded H2, using a single
 * {@link Connection} as the only enrolled resource.
 *
 * <p>Currently {@link Disabled} — un-comment when {@link MansartTransactionManager} M1 lands and
 * the resource enrolment hook (M4) is wired. Listed here as the integration target so the spec
 * stays in front of contributors.
 */
@Disabled("Pending M1 (begin/commit) and M4 (resource enrolment).")
class H2SingleResourceCommitIT {

    @Test
    void writeCommittedIsVisibleAfterTxCloses() throws Exception {
        TransactionManager tm = new MansartTransactionManager();
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:tx-test;DB_CLOSE_DELAY=-1", "sa", "")) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE t (id INT)");
            }
            tm.begin();
            // TODO M4: tm.getTransaction().enlistResource(new ConnectionResource(c));
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
