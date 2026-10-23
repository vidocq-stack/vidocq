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

package io.vidocq.bodoni.core.tags.sql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.vidocq.bodoni.core.testing.TestDatabase;

/** The test database of the {@code sql:} handler tests: a {@code book} table of three rows. */
final class Books {

    /** Every title, in id order. */
    static final String ALL = "SELECT title FROM book ORDER BY id";

    private Books() {
    }

    /** A fresh database: books 1 {@code Manuale}, 2 {@code Tipografico} and 3 {@code Parma}. */
    static TestDatabase create() {
        return TestDatabase.create(
                "CREATE TABLE book(id INT PRIMARY KEY, title VARCHAR(40))",
                "INSERT INTO book VALUES (1, 'Manuale')",
                "INSERT INTO book VALUES (2, 'Tipografico')",
                "INSERT INTO book VALUES (3, 'Parma')");
    }

    /** A data source that was never configured: {@code getConnection()} fails, as the TCK's unset wrapper. */
    static final class Unconfigured implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("no URL configured");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("no URL configured");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // a test double: nothing to log
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // a test double: no timeout
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
