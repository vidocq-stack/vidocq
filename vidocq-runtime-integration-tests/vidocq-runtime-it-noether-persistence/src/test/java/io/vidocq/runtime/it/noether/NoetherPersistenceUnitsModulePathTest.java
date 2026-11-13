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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Type;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Two units in one application module: {@code ledger} on the default pool, {@code archive} on the named pool
 * {@code notes}. Each lists its entity and excludes the unlisted ones, so neither takes the other's. The
 * {@code archive} unit names the qualifier {@link Archived}, so it is also injected with {@code @Inject}
 * (Jakarta EE 11 Platform §13.12).
 */
class NoetherPersistenceUnitsModulePathTest {

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

    private static Ledger ledger() {
        return CDI.current().select(Ledger.class).get();
    }

    private static Archive archive() {
        return CDI.current().select(Archive.class).get();
    }

    @Test
    void each_unit_manages_the_entities_it_lists_only() {
        assertEquals(Set.of(Entry.class), entities(ledger().factory()));
        assertEquals(Set.of(Note.class), entities(archive().factory()));
    }

    @Test
    void the_unit_that_names_a_pool_writes_to_its_database_only() throws SQLException {
        archive().record("archived");

        assertEquals(1, archive().count("archived"));
        assertEquals(1, tables(CDI.current().select(DataSource.class, NamedLiteral.of("notes")).get(), "NOTE"),
                "the archive unit created its table in vidocq.pool.notes.url's database");
        assertEquals(0, tables(CDI.current().select(DataSource.class).get(), "NOTE"),
                "a unit of the default pool manages Note too and created its table there");
    }

    @Test
    void the_unit_its_descriptor_qualifies_is_injected_with_inject() {
        archivist().record("injected");

        assertEquals(1, archive().count("injected"));
        assertSame(archive().factory().getMetamodel(), archivist().factory().getMetamodel(),
                "@Inject @Archived and @PersistenceUnit(unitName = \"archive\") reach one factory");
    }

    @Test
    void the_injected_entity_manager_shares_the_persistence_context_of_its_transaction() {
        assertTrue(archivist().sharesThePersistenceContextOfTheUnit("shared"),
                "the @PersistenceContext of the unit manages what @Inject @Archived persisted");
    }

    @Test
    void the_factory_bean_of_a_unit_is_named_after_it() {
        Set<Bean<?>> beans = CDI.current().getBeanManager().getBeans("archive");

        assertEquals(1, beans.size());
        assertTrue(beans.iterator().next().getTypes().contains(EntityManagerFactory.class));
    }

    private static Archivist archivist() {
        return CDI.current().select(Archivist.class).get();
    }

    private static Set<Class<?>> entities(EntityManagerFactory factory) {
        return factory.getMetamodel().getEntities().stream().map(Type::getJavaType).collect(Collectors.toSet());
    }

    private static long tables(DataSource pool, String table) throws SQLException {
        try (var connection = pool.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                     + " WHERE UPPER(TABLE_NAME) = ?")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
