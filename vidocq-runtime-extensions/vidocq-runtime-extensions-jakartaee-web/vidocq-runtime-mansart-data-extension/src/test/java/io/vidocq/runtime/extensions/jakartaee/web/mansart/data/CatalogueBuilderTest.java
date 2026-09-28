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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Gadget;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.GadgetRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.GadgetRepositoryImpl;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.ReportQueries;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link CatalogueBuilder}: the repositories and Mansart's entity models, as one immutable catalogue. */
class CatalogueBuilderTest {

    private static final Function<Class<?>, EntityModel<?>> MODELS = CatalogueFixtures::model;

    /** A second class whose simple name is {@code Gadget}. */
    static final class Twin {
        static final class Gadget {}
    }

    private static MansartDataCatalogue build() {
        return CatalogueBuilder.DEFAULT.build(CatalogueFixtures.REPOSITORIES, MODELS);
    }

    private static Entity entity(MansartDataCatalogue catalogue, String name) {
        return catalogue.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theRepositoriesOfTheBeansEachOnceTheGeneratedImplementationIncluded() {
        assertEquals(List.of(GadgetRepository.class, ReportQueries.class), CatalogueBuilder.repositoryInterfaces(
                List.of(GadgetRepositoryImpl.class, GadgetRepository.class, String.class, ReportQueries.class)));
    }

    @Test
    void theCatalogueCountsAndOrdersEverything() {
        MansartDataCatalogue catalogue = build();

        assertEquals(List.of("Broken", "Customer", "Gadget", "Order"),
                catalogue.entities().stream().map(Entity::name).toList());
        assertEquals(List.of("BrokenRepository", "CustomerRepository", "GadgetRepository", "OrderRepository",
                "ReportQueries"), catalogue.repositories().stream().map(MansartDataCatalogue.Repository::name).toList());
        assertEquals(4, catalogue.entityCount());
        assertEquals(5, catalogue.repositoryCount());
        assertEquals(14, catalogue.methodCount());
        assertEquals(0, catalogue.moreEntities());
        assertEquals(0, catalogue.moreRepositories());
    }

    @Test
    void eachRepositoryNamesItsEntityAndCountsItsMethods() {
        MansartDataCatalogue catalogue = build();
        MansartDataCatalogue.Repository gadgets = catalogue.repositories().get(2);

        assertEquals("GadgetRepository", gadgets.name());
        assertEquals(GadgetRepository.class.getName(), gadgets.className());
        assertEquals(Gadget.class.getName(), gadgets.entityClassName());
        assertEquals("Gadget", gadgets.entityName());
        assertEquals("Long", gadgets.idType());
        assertEquals(10, gadgets.methodCount());
        assertEquals(10, gadgets.methods().size());
        assertEquals(List.of("GadgetRepository"), catalogue.repositoriesOf(entity(catalogue, "Gadget")).stream()
                .map(MansartDataCatalogue.Repository::name).toList());
        assertEquals(List.of("ReportQueries"),
                catalogue.otherRepositories().stream().map(MansartDataCatalogue.Repository::name).toList());
        assertNull(catalogue.otherRepositories().getFirst().entityName());
    }

    @Test
    void anEntityBuiltAtRunTimeByMansart() {
        Entity gadget = entity(build(), "Gadget");

        assertEquals("gadgets", gadget.table());
        assertNull(gadget.failure());
        assertEquals(List.of(
                new Column("id", "id", "Long", "id", false, true),
                new Column("label", "label", "String", "", true, false),
                new Column("stockCount", "stock_count", "Integer", "", true, false)), gadget.columns());
    }

    @Test
    void theIdFirstThenTheVersionThenTheOthersInModelOrder() {
        Entity order = entity(build(), "Order");

        assertEquals("shop.orders", order.table());
        assertEquals(List.of(
                new Column("id", "id", "UUID", "id", false, true),
                new Column("version", "row_version", "Integer", "version", false, false),
                new Column("note", "note", "String", "", true, true),
                new Column("customer", "customer_id", "Customer", "→ Customer", true, false),
                new Column("status", "status", "Status", "enum", false, false),
                new Column("name", "name", "String", "joined", true, false)), order.columns());
    }

    @Test
    void anEntityWhoseModelFailsKeepsItsPlaceWithTheExceptionClass() {
        Entity broken = entity(build(), "Broken");

        assertEquals(Broken.class.getName(), broken.className());
        assertEquals("", broken.table());
        assertEquals(List.of(), broken.columns());
        assertEquals("io.vidocq.mansart.data.core.MansartDataException", broken.failure());
    }

    @Test
    void anUnusableModelIsSaidSo() {
        MansartDataCatalogue catalogue = CatalogueBuilder.DEFAULT.build(List.of(GadgetRepository.class), type -> null);

        assertEquals(CatalogueBuilder.UNUSABLE, catalogue.entities().getFirst().failure());
    }

    @Test
    void aLinkageErrorFromTheModelIsReportedNotThrown() {
        MansartDataCatalogue catalogue = CatalogueBuilder.DEFAULT.build(List.of(GadgetRepository.class), type -> {
            throw new NoClassDefFoundError("io/acme/_Gadget");
        });

        assertEquals("java.lang.NoClassDefFoundError", catalogue.entities().getFirst().failure());
    }

    @Test
    void pastALimitTheRestIsCountedNotListed() {
        MansartDataCatalogue catalogue =
                new CatalogueBuilder(1, 3, 3, 1_000).build(CatalogueFixtures.REPOSITORIES, MODELS);

        assertEquals(List.of("Broken"), catalogue.entities().stream().map(Entity::name).toList());
        assertEquals(3, catalogue.moreEntities());
        assertEquals(List.of("BrokenRepository", "CustomerRepository", "GadgetRepository"),
                catalogue.repositories().stream().map(MansartDataCatalogue.Repository::name).toList());
        assertEquals(2, catalogue.moreRepositories());
        MansartDataCatalogue.Repository gadgets = catalogue.repositories().get(2);
        assertEquals(List.of("add", "byLabel", "change"),
                gadgets.methods().stream().map(MansartDataCatalogue.Method::name).toList());
        assertEquals(7, gadgets.moreMethods());
        assertEquals(14, catalogue.methodCount(), "every declared method is counted");
    }

    @Test
    void aLongQueryIsCut() {
        MansartDataCatalogue catalogue =
                new CatalogueBuilder(200, 200, 200, 10).build(List.of(GadgetRepository.class), MODELS);

        assertEquals("FROM Gadge…", catalogue.repositories().getFirst().methods().stream()
                .filter(m -> m.name().equals("search")).findFirst().orElseThrow().query());
    }

    @Test
    void twoTypesWithOneSimpleNameAreShownUnderTheirFullNames() {
        Map<Class<?>, String> names = CatalogueBuilder.names(List.of(Gadget.class, Twin.Gadget.class, Broken.class));

        assertEquals(Gadget.class.getName(), names.get(Gadget.class));
        assertEquals(Twin.Gadget.class.getName(), names.get(Twin.Gadget.class));
        assertEquals("Broken", names.get(Broken.class));
    }
}
