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

package io.vidocq.bodoni.core.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A fresh in-memory test database, reached through {@link DriverManager} only so that no test
 * class depends on the driver's own types.
 */
public final class TestDatabase implements AutoCloseable {

    private static final String DRIVER = "org.h2.Driver";
    private static final String USER = "sa";

    private final String url;

    private TestDatabase(String url) {
        this.url = url;
    }

    /** Creates a database and runs {@code statements} in order. */
    public static TestDatabase create(String... statements) {
        TestDatabase db = new TestDatabase("jdbc:h2:mem:bodoni-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return db;
    }

    public String url() {
        return url;
    }

    /** The {@code url,driver,user,password} form of the {@code sql:setDataSource} attribute. */
    public String jdbcParameters() {
        return url + "," + DRIVER + "," + USER + ",";
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, USER, "");
    }

    /** Runs {@code select} and returns its rows as lists of {@code getObject} values. */
    public List<List<Object>> rows(String select) {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<List<Object>> out = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.add(rs.getObject(i));
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Drops the database. */
    @Override
    public void close() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
