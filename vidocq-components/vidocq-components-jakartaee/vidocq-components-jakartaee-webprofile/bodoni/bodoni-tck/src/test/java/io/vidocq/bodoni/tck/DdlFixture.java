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
package io.vidocq.bodoni.tck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * A small Derby-style DDL of Bodoni's own, for the runner's tests: never the TCK file (EFTL). Like the
 * official DDL it starts with a {@code drop table} that fails on a fresh database, quotes a quote, and
 * uses a JDBC date escape and a {@code rank} column.
 */
final class DdlFixture {
    static final String SCRIPT = """
            drop table t1 ;
            create table t1(idNum INT NOT NULL, firstName VARCHAR(20) NOT NULL, primary key(idNum)) ;
            create table t2(idNum INT NOT NULL, dob DATE NOT NULL, rank INT NOT NULL, rating NUMERIC(10,2)) ;
            INSERT INTO t1 VALUES(1, 'Giambattista') ;
            INSERT INTO t1 VALUES(2, 'O''Bodoni') ;
            INSERT INTO t2(idNum, dob, rank, rating) VALUES (1, {d '1999-05-05'}, 1, NULL) ;
            """;

    private DdlFixture() {
    }

    /** A database name no other test, and no listener-started database, uses. */
    static String uniqueName() {
        return TckDatabase.NAME + "-" + UUID.randomUUID();
    }

    /** Writes {@code script} to {@code ddl.sql} in {@code directory} and returns its path. */
    static Path write(Path directory, String script) throws IOException {
        return Files.writeString(directory.resolve("ddl.sql"), script);
    }

    /** Counts the rows of {@code t1} through a new connection to {@code url}. */
    static int countT1(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, TckDatabase.USER, TckDatabase.PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM t1")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
