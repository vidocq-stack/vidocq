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

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MANSART-036: a repository whose table does not exist failed with a bare "SQL error". The message now names the
 * entity, the table Mansart looked for, how it chose that name, and the two ways out.
 */
class MissingTableTest {

    private static MansartData stack(String db, String... ddl) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String statement : ddl) {
                s.execute(statement);
            }
        }
        return MansartData.builder().dataSource(ds).build();
    }

    @Test
    void saveOnAnEmptyDatabaseNamesTheEntityTheDefaultTableAndTheFixes() throws Exception {
        AuthorRepository authors = stack("mansart-missing-empty").repository(AuthorRepository.class);
        Author hugo = new Author();
        hugo.setName("Victor Hugo");

        assertThatThrownBy(() -> authors.save(hugo))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining(Author.class.getName())
                .hasMessageContaining("\"authors\"")
                .hasMessageContaining("Author → authors")
                .hasMessageContaining("@Table(name = ")
                .hasMessageContaining("db/migration");
    }

    @Test
    void readsAndDerivedQueriesNameTheMissingTableWhenOtherTablesExist() throws Exception {
        AuthorRepository authors = stack("mansart-missing-other",
                "CREATE TABLE \"other\" (\"id\" BIGINT PRIMARY KEY)").repository(AuthorRepository.class);

        assertThatThrownBy(authors::findAll).isInstanceOf(MansartDataException.class)
                .hasMessageContaining("\"authors\"");
        assertThatThrownBy(authors::count).isInstanceOf(MansartDataException.class)
                .hasMessageContaining("\"authors\"");
        assertThatThrownBy(() -> authors.findByName("Zola")).isInstanceOf(MansartDataException.class)
                .hasMessageContaining("\"authors\"");
    }

    @Test
    void aTableNamedByTableIsReportedAsSuch() throws Exception {
        RepositoryRuntime runtime = stack("mansart-missing-table-annotation").runtime();

        assertThatThrownBy(() -> runtime.findAll(EntityModels.of(Crate.class)))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining(Crate.class.getName())
                .hasMessageContaining("\"jdql_crates\"")
                .hasMessageContaining("named by its @Table");
    }

    /** H2 upper-cases an unquoted name: {@code CREATE TABLE authors} makes {@code AUTHORS}, which Mansart's quoted
     * {@code "authors"} does not match; H2 then answers 42S03 (a table of another case exists). */
    @Test
    void anUnquotedH2TableOfAnotherCaseIsReportedToo() throws Exception {
        AuthorRepository authors = stack("mansart-missing-case",
                "CREATE TABLE authors (id BIGINT PRIMARY KEY, name VARCHAR(200))").repository(AuthorRepository.class);

        assertThatThrownBy(authors::findAll).isInstanceOf(MansartDataException.class)
                .hasMessageContaining("\"authors\"")
                .hasMessageContaining("DATABASE_TO_LOWER=TRUE");
    }

    @Test
    void anotherSqlErrorKeepsItsMessage() throws Exception {
        AuthorRepository authors = stack("mansart-missing-column",
                "CREATE TABLE \"authors\" (\"id\" BIGINT PRIMARY KEY)").repository(AuthorRepository.class);

        assertThatThrownBy(authors::findAll).isInstanceOf(MansartDataException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage()).doesNotContain("does not exist"));
    }
}
