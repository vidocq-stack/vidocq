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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Works in the application's transactions through what JNDI answers for a pool (Vidocq/vidocq#676): the factory
 * of a JTA unit that the application created itself with {@code Persistence.createEntityManagerFactory}, and the
 * data source itself.
 */
@ApplicationScoped
public class Cashier {

    @Transactional
    public void record(EntityManagerFactory receipts, String id) {
        persist(receipts, id);
    }

    @Transactional
    public void recordThenFail(EntityManagerFactory receipts, String id) {
        persist(receipts, id);
        throw new IllegalStateException("refused after persisting " + id);
    }

    /** Plain JDBC on a data source a JNDI lookup answered, in the application's transaction. */
    @Transactional
    public void insertThenFail(DataSource pool, String table, String id) throws SQLException {
        try (Connection connection = pool.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " (ID) VALUES (?)")) {
            insert.setString(1, id);
            insert.executeUpdate();
        }
        throw new IllegalStateException("refused after inserting " + id);
    }

    private static void persist(EntityManagerFactory receipts, String id) {
        EntityManager manager = receipts.createEntityManager();
        try {
            manager.joinTransaction();
            manager.persist(new Receipt(id));
        } finally {
            manager.close();
        }
    }
}
