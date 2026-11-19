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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Type;
import jakarta.transaction.TransactionScoped;
import java.lang.annotation.Annotation;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The units of one application module: {@code ledger} on the default pool, {@code archive} on the named pool
 * {@code notes}, {@code catalog} on the default pool. Each lists its entity and excludes the unlisted ones, so none
 * takes another's. The {@code archive} and {@code catalog} units name a qualifier, {@link Archived} and
 * {@link Catalogued}, so they are also injected with {@code @Inject} (Jakarta EE 11 Platform §13.12); the
 * {@code catalog} unit also names a scope, which its {@code EntityManager} bean takes in place of
 * {@code @TransactionScoped} (Jakarta Persistence 3.2, §8.2.1.6).
 */
class NoetherPersistenceUnitsModulePathTest {

    private static final class ArchivedLiteral extends AnnotationLiteral<Archived> implements Archived {
    }

    private static final class CataloguedLiteral extends AnnotationLiteral<Catalogued> implements Catalogued {
    }

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

    @Test
    void the_entity_manager_of_a_unit_takes_the_scope_its_descriptor_names() {
        assertEquals(ApplicationScoped.class, entityManager(new CataloguedLiteral()).getScope(),
                "<scope>jakarta.enterprise.context.ApplicationScoped</scope>");
        assertEquals(TransactionScoped.class, entityManager(new ArchivedLiteral()).getScope(),
                "a unit that names no scope keeps the default one");
    }

    @Test
    void an_application_scoped_entity_manager_writes_in_a_transaction_and_answers_outside_one() throws SQLException {
        Cataloguer cataloguer = CDI.current().select(Cataloguer.class).get();

        cataloguer.add("kept");

        assertEquals(1, cards(CDI.current().select(DataSource.class).get(), "kept"),
                "the transaction of the method committed the card");
        assertEquals("kept", cataloguer.find("kept").id(), "outside any transaction, its context is active");
        EntityManager transactionScoped = CDI.current().select(EntityManager.class, new ArchivedLiteral()).get();
        assertThrows(ContextNotActiveException.class, () -> transactionScoped.find(Note.class, "kept"),
                "where the entity manager of a unit without <scope> has no context outside a transaction");
    }

    private static Archivist archivist() {
        return CDI.current().select(Archivist.class).get();
    }

    private static Bean<?> entityManager(Annotation qualifier) {
        BeanManager beans = CDI.current().getBeanManager();
        return beans.resolve(beans.getBeans(EntityManager.class, qualifier));
    }

    private static long cards(DataSource pool, String id) throws SQLException {
        try (var connection = pool.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM CARD WHERE ID = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
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
