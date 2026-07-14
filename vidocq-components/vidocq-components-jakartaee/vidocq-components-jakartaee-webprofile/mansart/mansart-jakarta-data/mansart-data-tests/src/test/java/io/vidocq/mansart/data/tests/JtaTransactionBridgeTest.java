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
package io.vidocq.mansart.data.tests;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MANSART-007 — the JTA transaction governs mansart-data connections. Repository writes made
 * inside an active transaction must be undone by a rollback and applied atomically by a commit,
 * for both enlistment paths:
 * <ul>
 *   <li>{@code @Default} datasource = {@code PlainDataSource} (XA capability hidden) → LRCO
 *       fallback ({@code ConnectionXAResource});</li>
 *   <li>{@code "bridgeds"} routed datasource = bare H2 {@code JdbcDataSource} → real
 *       {@code XADataSource} enlistment.</li>
 * </ul>
 * Before the bridge, every test asserting a rollback here was red: each repository operation
 * ran on its own autocommit connection and the write survived the rollback.
 */
class JtaTransactionBridgeTest {

    private static VaubanContainer container;
    private static AuthorRepository authors;
    private static BridgeNoteRepository bridgeNotes;
    private static XaOnlyNoteRepository xaOnlyNotes;
    private static TxWritingService service;
    private static TransactionManager tm;

    @BeforeAll
    static void boot() throws Exception {
        createSchemas();
        container = VaubanContainer.builder()
                .addBeanClass(io.vidocq.mansart.data.cdi.MansartDataExtension.class)
                .addBeanClass(io.vidocq.mansart.data.cdi.MansartRuntimeProducer.class)
                .addBeanClass(io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension.class)
                .addBeanClass(io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer.class)
                .addBeanClass(io.vidocq.mansart.transactions.cdi.TransactionalInterceptor.class)
                .addBeanClass(BridgeDataSources.class)
                .addBeanClass(Author.class)
                .addBeanClass(AuthorRepository.class)
                .addBeanClass(AuthorRepositoryImpl.class)
                .addBeanClass(BridgeNote.class)
                .addBeanClass(BridgeNoteRepository.class)
                .addBeanClass(BridgeNoteRepositoryImpl.class)
                .addBeanClass(XaOnlyNote.class)
                .addBeanClass(XaOnlyNoteRepository.class)
                .addBeanClass(XaOnlyNoteRepositoryImpl.class)
                .addBeanClass(TxWritingService.class)
                .build();
        authors     = container.select(AuthorRepository.class);
        bridgeNotes = container.select(BridgeNoteRepository.class);
        xaOnlyNotes = container.select(XaOnlyNoteRepository.class);
        service     = container.select(TxWritingService.class);
        tm          = container.select(TransactionManager.class);
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @org.junit.jupiter.api.AfterEach
    void rollbackLeftoverTransaction() throws Exception {
        // A failing assertion inside an open TX must not poison the next test.
        if (tm.getTransaction() != null) tm.rollback();
    }

    @BeforeEach
    void resetRows() throws Exception {
        try (Connection c = BridgeDataSources.h2(BridgeDataSources.DEFAULT_URL).getConnection();
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM \"authors\"");
        }
        try (Connection c = BridgeDataSources.h2(BridgeDataSources.AUDIT_URL).getConnection();
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM \"bridge_notes\"");
        }
        try (Connection c = BridgeDataSources.h2(BridgeDataSources.XAONLY_URL).getConnection();
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM \"xa_only_notes\"");
        }
    }

    private static void createSchemas() throws Exception {
        try (Connection c = BridgeDataSources.h2(BridgeDataSources.DEFAULT_URL).getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS \"authors\" ("
                    + "  \"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "  \"name\" VARCHAR(200) NOT NULL"
                    + ")");
        }
        try (Connection c = BridgeDataSources.h2(BridgeDataSources.AUDIT_URL).getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS \"bridge_notes\" ("
                    + "  \"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "  \"text\" VARCHAR(200) NOT NULL"
                    + ")");
        }
        try (Connection c = BridgeDataSources.h2(BridgeDataSources.XAONLY_URL).getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS \"xa_only_notes\" ("
                    + "  \"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "  \"text\" VARCHAR(200) NOT NULL"
                    + ")");
        }
    }

    /** Row count as seen by an independent connection — never the transaction's own view. */
    private static int countRows(String url, String table) throws Exception {
        try (Connection c = BridgeDataSources.h2(url).getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Author author(String name) {
        Author a = new Author();
        a.setName(name);
        return a;
    }

    @Test
    void rollbackDiscardsRepositoryWrites() throws Exception {
        tm.begin();
        authors.save(author("must vanish"));
        tm.rollback();

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors"))
                .as("a JTA rollback must undo repository writes (LRCO path)")
                .isZero();
    }

    @Test
    void commitAppliesWritesAtomically() throws Exception {
        tm.begin();
        authors.save(author("first"));
        authors.save(author("second"));
        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors"))
                .as("uncommitted writes must not be visible to other connections")
                .isZero();
        tm.commit();

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors")).isEqualTo(2);
    }

    @Test
    void interceptorRollbackDiscardsWrites() throws Exception {
        assertThatThrownBy(() -> service.saveAndFail("doomed"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors"))
                .as("@Transactional interceptor rollback must undo the repository write")
                .isZero();
    }

    @Test
    void interceptorCommitAppliesWrites() throws Exception {
        service.saveTwo("alpha", "beta");

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors")).isEqualTo(2);
    }

    @Test
    void multiDataSourceRollbackUndoesBothStores() throws Exception {
        tm.begin();
        authors.save(author("default-store write"));   // LRCO (plain) datasource
        BridgeNote note = new BridgeNote();
        note.setText("routed XA write");
        bridgeNotes.save(note);                        // routed repository, real-XA datasource
        tm.rollback();

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors"))
                .as("rollback must undo the plain-datasource write").isZero();
        assertThat(countRows(BridgeDataSources.AUDIT_URL, "bridge_notes"))
                .as("rollback must undo the routed XA-datasource write").isZero();
    }

    @Test
    void multiDataSourceCommitAppliesBothStores() throws Exception {
        tm.begin();
        authors.save(author("kept"));
        BridgeNote note = new BridgeNote();
        note.setText("kept too");
        bridgeNotes.save(note);
        tm.commit();

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors")).isEqualTo(1);
        assertThat(countRows(BridgeDataSources.AUDIT_URL, "bridge_notes")).isEqualTo(1);
    }

    @Test
    void twoRealXaBranchesCommitAtomically() throws Exception {
        // Both datasources are real XADataSources ("bridgeds" + "xaonly") — a transaction
        // touching both enlists TWO XA branches: full two-phase commit, no LRCO involved.
        tm.begin();
        BridgeNote note = new BridgeNote();
        note.setText("xa branch 1");
        bridgeNotes.save(note);
        XaOnlyNote other = new XaOnlyNote();
        other.setText("xa branch 2");
        xaOnlyNotes.save(other);
        tm.commit();

        assertThat(countRows(BridgeDataSources.AUDIT_URL, "bridge_notes")).isEqualTo(1);
        assertThat(countRows(BridgeDataSources.XAONLY_URL, "xa_only_notes")).isEqualTo(1);
    }

    @Test
    void twoRealXaBranchesRollBackAtomically() throws Exception {
        tm.begin();
        BridgeNote note = new BridgeNote();
        note.setText("must vanish 1");
        bridgeNotes.save(note);
        XaOnlyNote other = new XaOnlyNote();
        other.setText("must vanish 2");
        xaOnlyNotes.save(other);
        tm.rollback();

        assertThat(countRows(BridgeDataSources.AUDIT_URL, "bridge_notes")).isZero();
        assertThat(countRows(BridgeDataSources.XAONLY_URL, "xa_only_notes")).isZero();
    }

    @Test
    void xaOnlyNamedDataSourceWorksOutsideTransaction() throws Exception {
        XaOnlyNote note = new XaOnlyNote();
        note.setText("adapter path");
        xaOnlyNotes.save(note);

        assertThat(countRows(BridgeDataSources.XAONLY_URL, "xa_only_notes"))
                .as("an @Named XADataSource bean must be adapted, not rejected")
                .isEqualTo(1);
    }

    @Test
    void xaOnlyNamedDataSourceJoinsTransactionRollback() throws Exception {
        tm.begin();
        XaOnlyNote note = new XaOnlyNote();
        note.setText("must vanish");
        xaOnlyNotes.save(note);
        tm.rollback();

        assertThat(countRows(BridgeDataSources.XAONLY_URL, "xa_only_notes"))
                .as("the adapted XADataSource must enlist through the real-XA path")
                .isZero();
    }

    @Test
    void outsideTransactionBehaviourUnchanged() throws Exception {
        authors.save(author("immediate"));

        assertThat(countRows(BridgeDataSources.DEFAULT_URL, "authors"))
                .as("outside any transaction, writes stay per-operation autocommit")
                .isEqualTo(1);
    }
}
