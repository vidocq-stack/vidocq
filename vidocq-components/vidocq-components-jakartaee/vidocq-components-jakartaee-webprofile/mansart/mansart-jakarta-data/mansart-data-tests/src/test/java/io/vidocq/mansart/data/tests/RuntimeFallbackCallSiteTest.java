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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A JDQL statement the processor leaves to the run time (an arithmetic {@code UPDATE}) hands {@code JdqlExecutor} the
 * repository method: the generated code resolves it once, in a holder class, not on every call.
 */
class RuntimeFallbackCallSiteTest {

    @TempDir
    Path out;

    @Test
    void theRepositoryMethodIsResolvedOnceAndTheStatementRuns() throws Exception {
        var result = InProcessCompilation.compileSources(out,
                "stock.Item", """
                        package stock;

                        @jakarta.persistence.Entity
                        public class Item {
                            @jakarta.persistence.Id
                            public Long id;
                            public int quantity;
                        }
                        """,
                "stock.Items", """
                        package stock;

                        import jakarta.data.repository.Param;
                        import jakarta.data.repository.Query;

                        @jakarta.data.repository.Repository
                        public interface Items extends jakarta.data.repository.BasicRepository<Item, Long> {
                            @Query("UPDATE Item SET quantity = quantity + :more WHERE id = :id")
                            int restock(@Param("more") int more, @Param("id") long id);
                        }
                        """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("stock.ItemsImpl"))
                .contains("static final java.lang.reflect.Method M = io.vidocq.mansart.data.core.JdqlExecutor"
                        + ".callSite(stock.Items.class, \"restock\", int.class, long.class)")
                .doesNotContain("getInterfaces()[0]");

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:runtime-fallback-call-site;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE \"items\" (\"id\" BIGINT PRIMARY KEY, \"quantity\" INT)");
            s.execute("INSERT INTO \"items\" VALUES (1, 2)");
        }
        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> items = loader.loadClass("stock.Items");
            Object repository = MansartData.builder().dataSource(ds).build().repository(items);
            var restock = items.getMethod("restock", int.class, long.class);

            assertThat(restock.invoke(repository, 3, 1L)).isEqualTo(1);
            assertThat(restock.invoke(repository, 3, 1L)).isEqualTo(1);
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT \"quantity\" FROM \"items\" WHERE \"id\" = 1")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(8);
        }
    }
}
