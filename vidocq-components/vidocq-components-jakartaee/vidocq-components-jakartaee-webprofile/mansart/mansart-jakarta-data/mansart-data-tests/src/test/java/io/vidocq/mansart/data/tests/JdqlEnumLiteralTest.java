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
import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enum literals written as {@code pkg.Enum.CONSTANT} in a JDQL statement given as text (MANSART-023): they resolve
 * against the enum type of the attribute they meet, and never load a class whose name the statement chooses.
 */
class JdqlEnumLiteralTest {

    /** A class name no attribute of {@link Crate} uses, that a statement names to make Mansart load it. */
    private static final String FOREIGN_PACKAGE = "com.example.payload";

    private static DataSource dataSource;
    private static RepositoryRuntime runtime;
    private static EntityModel<Crate> model;

    @BeforeAll
    static void initStack() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-jdql-enum-literal;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        dataSource = ds;
        runtime = MansartData.builder().dataSource(ds).build().runtime();
        model = EntityModels.of(Crate.class);
    }

    @BeforeEach
    void resetRows() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS \"jdql_crates\"");
            s.execute("CREATE TABLE \"jdql_crates\" (\"id\" BIGINT PRIMARY KEY, \"label\" VARCHAR(60),"
                    + " \"status\" VARCHAR(20), \"kind\" VARCHAR(20))");
            s.execute("INSERT INTO \"jdql_crates\" VALUES"
                    + " (1, 'com.example.payload.Gadget.RUN', 'PENDING', 'STANDARD'),"
                    + " (2, 'glass', 'SHIPPED', 'FRAGILE'),"
                    + " (3, 'books', 'DELIVERED', 'STANDARD')");
        }
    }

    private static List<Long> ids(String jdql) {
        JdqlResult result = JdqlExecutor.run(jdql, Map.of(), model, runtime);
        assertThat(result).isInstanceOf(JdqlResult.Entities.class);
        return ((JdqlResult.Entities) result).entities().stream().map(e -> ((Crate) e).getId()).toList();
    }

    @Test
    void aTopLevelEnumLiteralResolvesAgainstTheAttributesType() {
        assertThat(ids("FROM Crate WHERE status = io.vidocq.mansart.data.tests.ShipmentStatus.DELIVERED"))
                .containsExactly(3L);
    }

    @Test
    void aNestedEnumLiteralResolvesByItsCanonicalOrItsBinaryName() {
        assertThat(ids("FROM Crate WHERE kind = io.vidocq.mansart.data.tests.Crate.Kind.FRAGILE"))
                .containsExactly(2L);
        assertThat(ids("FROM Crate WHERE kind = 'io.vidocq.mansart.data.tests.Crate$Kind.FRAGILE'"))
                .containsExactly(2L);
    }

    @Test
    void anEnumLiteralInAnInListAndInASetClauseResolves() {
        assertThat(ids("FROM Crate WHERE status IN (io.vidocq.mansart.data.tests.ShipmentStatus.PENDING,"
                + " io.vidocq.mansart.data.tests.ShipmentStatus.SHIPPED) ORDER BY id")).containsExactly(1L, 2L);

        JdqlExecutor.run("UPDATE Crate SET kind = 'io.vidocq.mansart.data.tests.Crate$Kind.FRAGILE' WHERE id = 3",
                Map.of(), model, runtime);

        assertThat(ids("FROM Crate WHERE kind = FRAGILE ORDER BY id")).containsExactly(2L, 3L);
    }

    @Test
    void aLiteralNamingAnotherClassIsNeverLoaded() {
        RecordingLoader loader = new RecordingLoader(Thread.currentThread().getContextClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            assertThat(ids("FROM Crate WHERE label = 'com.example.payload.Gadget.RUN'")).containsExactly(1L);
            assertThat(ids("FROM Crate WHERE label = com.example.payload.Gadget.RUN")).containsExactly(1L);
            assertThatThrownBy(() -> ids("FROM Crate WHERE kind = com.example.payload.Gadget.RUN"))
                    .isInstanceOf(MansartDataException.class)
                    .hasMessage("'com.example.payload.Gadget.RUN': not a Kind");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        assertThat(loader.requested).noneMatch(name -> name.startsWith(FOREIGN_PACKAGE));
    }

    @Test
    void aLiteralNamingAnotherEnumIsNotTakenForTheAttributesEnum() {
        assertThatThrownBy(() -> ids("FROM Crate WHERE kind = io.vidocq.mansart.data.tests.ShipmentStatus.PENDING"))
                .isInstanceOf(MansartDataException.class)
                .hasMessage("'io.vidocq.mansart.data.tests.ShipmentStatus.PENDING': not a Kind");
    }

    /** A class loader that records the name of each class it is asked for, then lets its parent load it. */
    private static final class RecordingLoader extends ClassLoader {

        final Queue<String> requested = new ConcurrentLinkedQueue<>();

        RecordingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            requested.add(name);
            return super.loadClass(name, resolve);
        }
    }
}
