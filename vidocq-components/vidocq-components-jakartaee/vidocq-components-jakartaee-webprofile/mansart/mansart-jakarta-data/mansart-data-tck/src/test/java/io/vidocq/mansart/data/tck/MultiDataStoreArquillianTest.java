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
package io.vidocq.mansart.data.tck;

import io.vidocq.mansart.data.cdi.MansartDataExtension;
import io.vidocq.mansart.data.dialect.h2.H2DialectFactory;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.h2.jdbcx.JdbcDataSource;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * M9 — proves that {@code @Repository(dataStore = "name")} is honoured by Mansart's CDI bootstrap:
 * the {@link io.vidocq.mansart.data.cdi.DataStoreResolver} routes each repository to the matching
 * {@code @Named} {@link DataSource} bean, building one isolated {@code RepositoryRuntime} per key.
 *
 * <p>Setup: three H2 DBs (default + primary + secondary). Each repository targets one of them via
 * the {@code dataStore} attribute. We seed each DB directly through JDBC, then assert each repo
 * only sees its own data — proving the routing actually splits the connection pool.
 */
public class MultiDataStoreArquillianTest extends Arquillian {

    @Entity
    @jakarta.persistence.Table(name = "items")
    public static class Item {
        @Id @GeneratedValue private Long id;
        private String label;
        public Long getId()        { return id;     }
        public void setId(Long id) { this.id = id;  }
        public String getLabel()         { return label;     }
        public void setLabel(String l)   { this.label = l;   }
    }

    @Repository(dataStore = "primary")
    public interface PrimaryItemRepository extends BasicRepository<Item, Long> {}

    @Repository(dataStore = "secondary")
    public interface SecondaryItemRepository extends BasicRepository<Item, Long> {}

    /**
     * Test-scope producer that publishes two {@code @Named} H2 DataSources. The {@code @Default}
     * DataSource comes from {@link H2DataSourceProducer}, contributed by
     * {@code MansartTckArchiveAppender} on every TCK deployment — we don't redeclare it here.
     */
    @Singleton
    public static class MultiDsProducer {
        @Produces @Singleton @Named("primary")
        public DataSource primaryDs() { return h2("mansart-multi-primary"); }
        @Produces @Singleton @Named("secondary")
        public DataSource secondaryDs() { return h2("mansart-multi-secondary"); }
        private static DataSource h2(String dbName) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            return ds;
        }
    }

    @Deployment
    public static Archive<?> deployment() {
        return ShrinkWrap.create(JavaArchive.class, "mansart-multi-datastore.jar")
                .addPackages(true, "io.vidocq.mansart.data.dialect")
                .addPackages(true, "io.vidocq.mansart.data.core")
                .addPackages(true, "io.vidocq.mansart.data.cdi")
                .addPackages(true, "io.vidocq.mansart.data.dialect.h2")
                .addClasses(MultiDataStoreArquillianTest.class,
                            Item.class,
                            PrimaryItemRepository.class,
                            SecondaryItemRepository.class,
                            MultiDsProducer.class)
                // No mansart-repositories.list — the runtime BCE @Enhancement path picks up
                // both repository interfaces, exercising DataStoreResolver in
                // MansartRuntimeRepoCreator (covers the proxy code path).
                .addAsResource(new StringAsset(MansartDataExtension.class.getName() + "\n"),
                        "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension")
                .addAsResource(new StringAsset(H2DialectFactory.class.getName() + "\n"),
                        "META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory");
    }

    @Inject PrimaryItemRepository   primary;
    @Inject SecondaryItemRepository secondary;
    @Inject @Named("primary")   DataSource primaryDs;
    @Inject @Named("secondary") DataSource secondaryDs;

    @BeforeMethod
    public void resetSchemas() throws Exception {
        recreateItems(primaryDs);
        recreateItems(secondaryDs);
        seed(primaryDs,   "primary-A");
        seed(primaryDs,   "primary-B");
        seed(secondaryDs, "secondary-X");
    }

    @Test
    public void primaryRepoOnlySeesPrimaryRows() {
        assertNotNull(primary, "PrimaryItemRepository should be injected");
        long n = primary.findAll().count();
        assertEquals(n, 2L, "primary repo must see exactly the rows seeded into the @Named(\"primary\") DataSource");
        boolean allPrimary = primary.findAll().allMatch(it -> it.getLabel().startsWith("primary-"));
        assertTrue(allPrimary, "primary repo must NOT see any rows from the @Named(\"secondary\") DataSource");
    }

    @Test
    public void secondaryRepoOnlySeesSecondaryRows() {
        assertNotNull(secondary, "SecondaryItemRepository should be injected");
        long n = secondary.findAll().count();
        assertEquals(n, 1L, "secondary repo must see exactly the row seeded into the @Named(\"secondary\") DataSource");
        boolean allSecondary = secondary.findAll().allMatch(it -> it.getLabel().startsWith("secondary-"));
        assertTrue(allSecondary, "secondary repo must NOT see any rows from the @Named(\"primary\") DataSource");
    }

    @Test
    public void writesGoToTheTargetedStoreOnly() {
        Item it = new Item();
        it.setLabel("primary-fresh");
        primary.save(it);

        long primaryCount   = primary.findAll().count();
        long secondaryCount = secondary.findAll().count();
        assertEquals(primaryCount,   3L, "save() through primary repo must land in the primary DB");
        assertEquals(secondaryCount, 1L, "save() through primary repo must NOT cross into the secondary DB");
    }

    private static void recreateItems(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS \"items\"");
            s.execute("CREATE TABLE \"items\" ("
                    + "  \"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                    + "  \"label\" VARCHAR(200) NOT NULL"
                    + ")");
        }
    }

    private static void seed(DataSource ds, String label) throws Exception {
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement("INSERT INTO \"items\"(\"label\") VALUES (?)")) {
            ps.setString(1, label);
            ps.executeUpdate();
        }
    }
}
