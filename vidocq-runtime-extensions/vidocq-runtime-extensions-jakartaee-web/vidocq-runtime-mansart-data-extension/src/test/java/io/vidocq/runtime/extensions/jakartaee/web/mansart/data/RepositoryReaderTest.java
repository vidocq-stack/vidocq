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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.BrokenRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Customer;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.CustomerRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Gadget;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.GadgetRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Order;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.OrderRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.ReportQueries;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Method;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link RepositoryReader}: what one {@code @Repository} interface says of itself, read by reflection. */
class RepositoryReaderTest {

    /** Raw: no type argument names an entity. */
    @SuppressWarnings("rawtypes")
    @Repository
    interface RawRepository extends BasicRepository {}

    /** The name reflection gives a parameter without {@code @Param}: real with -parameters, argN without. */
    private static String nameOf(String method, Class<?>... types) throws NoSuchMethodException {
        var parameter = GadgetRepository.class.getMethod(method, types).getParameters()[0];
        return parameter.isNamePresent() ? parameter.getName() : "arg0";
    }

    @Test
    void theDeclaredMethodsInNameOrderWithTheirKindQueryParametersAndReturns() throws Exception {
        RepositoryReader.Read read = RepositoryReader.read(GadgetRepository.class, 1_000);

        String gadget = nameOf("add", Gadget.class);
        assertEquals(List.of(
                new Method("add", "@Insert", "", gadget + ": Gadget", "Gadget"),
                new Method("byLabel", "@Find", "", nameOf("byLabel", String.class) + ": String", "Optional<Gadget>"),
                new Method("change", "@Update", "", gadget + ": Gadget", "Gadget"),
                new Method("countByStockCountGreaterThan", "derived", "", "min: int", "long"),
                new Method("findByLabel", "derived", "", nameOf("findByLabel", String.class) + ": String",
                        "List<Gadget>"),
                new Method("keep", "@Save", "", gadget + ": Gadget", "Gadget"),
                new Method("remove", "@Delete", "", gadget + ": Gadget", "void"),
                new Method("search", "JDQL", "FROM Gadget WHERE label LIKE :pattern ORDER BY id",
                        "pattern: String", "List<Gadget>"),
                new Method("stockCounts", "other", "", "", "int[]"),
                new Method("totals", "other", "", "", "Map<String, ? extends Number>")), read.methods());
    }

    @Test
    void thePrimaryEntityAndIdAndTheInheritedLine() {
        RepositoryReader.Read gadgets = RepositoryReader.read(GadgetRepository.class, 1_000);
        assertEquals(Gadget.class, gadgets.entity());
        assertEquals("Long", gadgets.idType());
        assertEquals("inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll",
                gadgets.inherits());

        RepositoryReader.Read orders = RepositoryReader.read(OrderRepository.class, 1_000);
        assertEquals(Order.class, orders.entity());
        assertEquals("UUID", orders.idType());
        assertEquals("inherits CrudRepository: delete, deleteAll, deleteById, findAll, findById, insert, insertAll,"
                + " save, saveAll, update, updateAll", orders.inherits());

        RepositoryReader.Read broken = RepositoryReader.read(BrokenRepository.class, 1_000);
        assertEquals(Broken.class, broken.entity());
        assertEquals(List.of(), broken.methods());
    }

    @Test
    void primaryTypesThroughAGenericBaseInterface() {
        RepositoryReader.Read customers = RepositoryReader.read(CustomerRepository.class, 1_000);

        assertEquals(Customer.class, customers.entity());
        assertEquals("String", customers.idType());
        assertEquals("", customers.inherits(), "DataRepository has no method to inherit");
        assertEquals(List.of(new Method("existsByName", "derived", "",
                        (CustomerRepository.class.getMethods()[0].getParameters()[0].isNamePresent() ? "name" : "arg0")
                                + ": String", "boolean")),
                customers.methods());
    }

    @Test
    void aRepositoryWithoutJakartaDataSuperInterfaceHasNoPrimaryEntity() {
        RepositoryReader.Read reports = RepositoryReader.read(ReportQueries.class, 1_000);

        assertNull(reports.entity());
        assertNull(reports.idType());
        assertEquals("", reports.inherits());
        assertEquals(List.of("gadgetCount", "orderCount"), reports.methods().stream().map(Method::name).toList());
        assertEquals("SELECT count(this) FROM Gadget", reports.methods().getFirst().query());
    }

    @Test
    void aRawBasicRepositoryHasNoPrimaryEntity() {
        RepositoryReader.Read raw = RepositoryReader.read(RawRepository.class, 1_000);

        assertNull(raw.entity());
        assertEquals("inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll",
                raw.inherits());
    }

    @Test
    void aLongQueryIsCut() {
        assertEquals("FROM Gadge…", RepositoryReader.read(GadgetRepository.class, 10).methods().stream()
                .filter(m -> m.name().equals("search")).findFirst().orElseThrow().query());
        assertEquals("abc", RepositoryReader.cut("abc", 3));
        assertEquals("ab…", RepositoryReader.cut("abc", 2));
    }

    @Test
    void aParameterIsNamedByParamThenByItsRealNameThenArgN() {
        assertEquals("pattern", RepositoryReader.parameterName("pattern", true, "p", 0));
        assertEquals("pattern", RepositoryReader.parameterName("pattern", false, "arg0", 0));
        assertEquals("label", RepositoryReader.parameterName(null, true, "label", 0));
        assertEquals("label", RepositoryReader.parameterName(" ", true, "label", 0));
        assertEquals("arg2", RepositoryReader.parameterName(null, false, "arg2", 2));
    }

    @Test
    void theDerivedRule() {
        for (String name : List.of("findByLabel", "countByProject", "existsByName", "deleteByStatus")) {
            assertEquals(true, RepositoryReader.derived(name), name);
        }
        for (String name : List.of("findAll", "byLabel", "searchByText", "count")) {
            assertEquals(false, RepositoryReader.derived(name), name);
        }
    }
}
