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
package io.vidocq.runtime.it.noether;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.sql.SQLException;
import java.util.Map;
import javax.naming.InitialContext;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A factory the application creates itself, with {@code Persistence.createEntityManagerFactory}, of a unit that names
 * its data source (Vidocq/vidocq#676): Noether looks the name up through JNDI, where the naming extension answers
 * the pool of that name, through the application's transactions.
 */
class NoetherPersistenceApplicationFactoryModulePathTest {

    private static final Map<String, String> CREATE_TABLES =
            Map.of("jakarta.persistence.schema-generation.database.action", "drop-and-create");

    private static VidocqBootstrap bootstrap;

    @BeforeAll
    static void boot() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    @Test
    void a_jta_unit_works_in_the_transactions_of_the_application() throws SQLException {
        try (EntityManagerFactory receipts = Persistence.createEntityManagerFactory("receipts", CREATE_TABLES)) {
            Cashier cashier = CDI.current().select(Cashier.class).get();

            cashier.record(receipts, "kept");
            assertThrows(IllegalStateException.class, () -> cashier.recordThenFail(receipts, "rolled-back"));

            assertEquals(1, count(receipts, Receipt.class, "kept"));
            assertEquals(0, count(receipts, Receipt.class, "rolled-back"), "the unit's work rolled back with the transaction");
            assertEquals(1, rowsInThePoolNamedNotes("RECEIPT"), "the unit wrote to the database of the pool it names");
        }
    }

    @Test
    void a_resource_local_unit_works_on_the_pool_it_names() throws SQLException {
        try (EntityManagerFactory vouchers = Persistence.createEntityManagerFactory("vouchers", CREATE_TABLES);
             EntityManager manager = vouchers.createEntityManager()) {
            manager.getTransaction().begin();
            manager.persist(new Voucher("issued"));
            manager.getTransaction().commit();

            assertEquals(1, count(vouchers, Voucher.class, "issued"));
            assertEquals(1, rowsInThePoolNamedNotes("VOUCHER"), "the unit wrote to the database of the pool it names");
        }
    }

    @Test
    void a_pool_answers_its_jndi_name_within_the_transaction_of_the_application() throws Exception {
        DataSource notes = InitialContext.doLookup("java:comp/env/notes");
        try (var connection = notes.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE LOOKED_UP (ID VARCHAR(40) PRIMARY KEY)");
        }
        Cashier cashier = CDI.current().select(Cashier.class).get();

        assertThrows(IllegalStateException.class, () -> cashier.insertThenFail(notes, "LOOKED_UP", "rolled-back"));

        assertEquals(0, rowsInThePoolNamedNotes("LOOKED_UP"), "the insert rolled back with the transaction");
    }

    @Test
    void the_default_data_source_of_the_platform_is_the_default_pool() throws Exception {
        DataSource byDefault = InitialContext.doLookup("java:comp/DefaultDataSource");

        try (var connection = byDefault.getConnection()) {
            assertEquals("jdbc:h2:mem:vidocq-it-noether", connection.getMetaData().getURL());
        }
    }

    private static long count(EntityManagerFactory factory, Class<?> entity, String id) {
        try (EntityManager manager = factory.createEntityManager()) {
            return manager.createQuery("SELECT COUNT(e) FROM " + entity.getSimpleName() + " e WHERE e.id = :id", Long.class)
                    .setParameter("id", id)
                    .getSingleResult();
        }
    }

    private static long rowsInThePoolNamedNotes(String table) throws SQLException {
        DataSource notes = CDI.current().select(DataSource.class, NamedLiteral.of("notes")).get();
        try (var connection = notes.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
