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

import io.vidocq.mansart.data.core.MansartData;
import jakarta.data.Limit;
import jakarta.data.Order;
import jakarta.data.Sort;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Query;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Query} methods on the runtime path with Jakarta Data control parameters ({@code Sort}, {@code Order},
 * {@code Sort[]}, {@code Limit}, {@code PageRequest}), array returns, projections, aggregates and empty collection
 * {@code IN} arguments.
 */
class RuntimeQueryControlsTest {

    private static final String ADA = "Ada";
    private static final String BOB = "Bob";
    private static final String CYD = "Cyd";
    private static final String NAME = "name";
    private static final String ANY = "%";
    private static final String NOBODY = "nobody";
    private static final String BOB_B = "Bob B.";

    /** Not annotated {@code @Repository}: only the runtime engine implements it. */
    public interface QueryAuthors extends BasicRepository<Author, Long> {

        @Query("WHERE name LIKE ?1")
        List<Author> sorted(String pattern, Sort<Author> sort);

        @Query("WHERE name LIKE ?1")
        List<Author> ordered(String pattern, Order<Author> order);

        @Query("WHERE name LIKE ?1")
        List<Author> sortedBy(String pattern, Sort<Author>[] sorts);

        @Query("WHERE name LIKE ?1 ORDER BY name")
        List<Author> limited(String pattern, Limit limit);

        @Query("WHERE name LIKE ?1 ORDER BY name")
        Author[] asArray(String pattern);

        @Query("SELECT name WHERE name LIKE ?1 ORDER BY name")
        List<String> names(String pattern, Limit limit);

        @Query("SELECT name WHERE name LIKE ?1 ORDER BY name")
        Page<String> namePage(String pattern, PageRequest pageRequest);

        @Query("SELECT name WHERE name LIKE ?1 ORDER BY name")
        String[] nameArray(String pattern);

        @Query("SELECT name, id WHERE name LIKE ?1 ORDER BY name")
        List<Object[]> pairs(String pattern, Limit limit);

        @Query("WHERE name IN ?1")
        Optional<Author> oneOf(List<String> names);

        @Query("WHERE name IN ?1")
        Stream<Author> streamOf(List<String> names);

        @Query("SELECT COUNT(this) WHERE name IN ?1")
        long countOf(List<String> names);

        @Query("WHERE name IN ?1")
        boolean anyOf(List<String> names);

        @Query("SELECT MAX(id) WHERE name = ?1")
        long maxIdOf(String name);

        @Query("SELECT SUM(id)")
        long sumOfIds();

        @Query("SELECT AVG(id)")
        double averageId();

        @Query("UPDATE Author SET name = ?1 WHERE name = ?2")
        boolean rename(String newName, String oldName);

        @Query("DELETE FROM Author WHERE name = ?1")
        int remove(String name);

        @Query("UPDATE Author SET name = ?1 WHERE name = ?2")
        void renameQuietly(String newName, String oldName);

        @Query("UPDATE Author SET name = ?1 WHERE name = ?2")
        Boolean renamedBoxed(String newName, String oldName);

        @Query("DELETE FROM Author WHERE name = ?1")
        Integer removeBoxed(String name);

        @Query("SELECT AVG(id) WHERE name = ?1")
        double averageIdOf(String name);

        @Query("SELECT MAX(id) WHERE name = ?1")
        float maxIdAsFloat(String name);

        @Query("SELECT MAX(id) WHERE name = ?1")
        int maxIdAsInt(String name);
    }

    private static DataSource dataSource;
    private static QueryAuthors repo;

    @BeforeAll
    static void initStack() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-runtime-query-controls;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        dataSource = ds;
        repo = MansartData.builder().dataSource(ds).build().runtimeRepository(QueryAuthors.class);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS \"authors\"");
            s.execute("CREATE TABLE \"authors\" ("
                    + "  \"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "  \"name\" VARCHAR(200) NOT NULL"
                    + ")");
        }
        for (String name : List.of(BOB, ADA, CYD)) {
            Author a = new Author();
            a.setName(name);
            repo.save(a);
        }
    }

    private static List<String> names(List<Author> authors) {
        return authors.stream().map(Author::getName).toList();
    }

    @Test
    void appliesSortOrderAndLimitArguments() {
        assertThat(names(repo.sorted(ANY, Sort.desc(NAME)))).containsExactly(CYD, BOB, ADA);
        assertThat(names(repo.ordered(ANY, Order.by(Sort.asc(NAME))))).containsExactly(ADA, BOB, CYD);
        @SuppressWarnings("unchecked")
        Sort<Author>[] sorts = new Sort[]{Sort.desc(NAME)};
        assertThat(names(repo.sortedBy(ANY, sorts))).containsExactly(CYD, BOB, ADA);
        assertThat(names(repo.limited(ANY, Limit.range(2, 3)))).containsExactly(BOB, CYD);
        assertThat(repo.limited(ANY, Limit.range(4, 5))).isEmpty();
        assertThat(repo.asArray(ANY)).extracting(Author::getName).containsExactly(ADA, BOB, CYD);
    }

    @Test
    void projectsWithLimitsPagesAndArrays() {
        assertThat(repo.names(ANY, Limit.of(2))).containsExactly(ADA, BOB);
        Page<String> second = repo.namePage(ANY, PageRequest.ofPage(2, 2, true));
        assertThat(second.content()).containsExactly(CYD);
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(repo.nameArray("B%")).containsExactly(BOB);
        assertThat(repo.pairs(ANY, Limit.of(1))).singleElement()
                .satisfies(row -> assertThat(row[0]).isEqualTo(ADA));
    }

    @Test
    void emptyCollectionArgumentsMatchNothing() {
        assertThat(repo.oneOf(List.of())).isEmpty();
        assertThat(repo.streamOf(List.of())).isEmpty();
        assertThat(repo.countOf(List.of())).isZero();
        assertThat(repo.anyOf(List.of())).isFalse();
        assertThat(repo.oneOf(List.of(BOB))).get().extracting(Author::getName).isEqualTo(BOB);
        assertThat(repo.countOf(List.of(ADA, CYD))).isEqualTo(2);
    }

    @Test
    void aggregatesAndUpdates() {
        long adaId = repo.oneOf(List.of(ADA)).orElseThrow().getId();
        long total = repo.findAll().mapToLong(Author::getId).sum();

        assertThat(repo.maxIdOf(ADA)).isEqualTo(adaId);
        assertThat(repo.maxIdOf("nobody")).isZero();
        assertThat(repo.sumOfIds()).isEqualTo(total);
        assertThat(repo.averageId()).isEqualTo(total / 3.0);
        assertThat(repo.rename("Ada L.", ADA)).isTrue();
        assertThat(repo.rename("x", "nobody")).isFalse();
        assertThat(repo.remove(BOB)).isEqualTo(1);
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void writesAndEmptyAggregatesFollowTheDeclaredReturnType() {
        repo.renameQuietly(BOB_B, BOB);
        assertThat(repo.renamedBoxed("Cyd C.", CYD)).isTrue();
        assertThat(repo.removeBoxed(BOB_B)).isEqualTo(1);
        assertThat(names(repo.findAll().toList())).containsExactlyInAnyOrder(ADA, "Cyd C.");
        assertThat(repo.averageIdOf(NOBODY)).isZero();
        assertThat(repo.maxIdAsFloat(NOBODY)).isZero();
        assertThat(repo.maxIdAsInt(NOBODY)).isZero();
    }
}
