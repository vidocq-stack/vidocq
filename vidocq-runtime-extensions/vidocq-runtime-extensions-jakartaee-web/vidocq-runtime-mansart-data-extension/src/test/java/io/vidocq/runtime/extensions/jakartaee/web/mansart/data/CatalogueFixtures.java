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

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.EnumStorage;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.DataRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interfaces and entities the catalogue is built from, as an application declares them. Gadget, Customer
 * and Broken go through Mansart's own {@code EntityModels.of} (no metamodel is generated here, so Mansart builds
 * their model at run time); the model of Order is written by hand, to hold every kind of attribute.
 */
final class CatalogueFixtures {

    private CatalogueFixtures() {}

    /** Three fields, the implicit id {@code id} first. */
    public static class Gadget {
        private Long id;
        private String label;
        private int stockCount;

        public Gadget() {}
    }

    /** No id field at all: Mansart cannot build its model. */
    public static class Broken {
        private String label;

        public Broken() {}
    }

    /** Its model is {@link #orderModel()}. */
    public static class Order {
        public Order() {}
    }

    public static class Customer {
        private String id;
        private String name;

        public Customer() {}
    }

    public enum Status { OPEN, SHIPPED }

    /** Every kind of method; ten declared methods. */
    @Repository
    public interface GadgetRepository extends BasicRepository<Gadget, Long> {

        List<Gadget> findByLabel(String label);

        long countByStockCountGreaterThan(@Param("min") int min);

        @Query("FROM Gadget WHERE label LIKE :pattern ORDER BY id")
        List<Gadget> search(@Param("pattern") String pattern);

        @Find
        Optional<Gadget> byLabel(@By("label") String label);

        @Insert
        Gadget add(Gadget gadget);

        @Update
        Gadget change(Gadget gadget);

        @Delete
        void remove(Gadget gadget);

        @Save
        Gadget keep(Gadget gadget);

        int[] stockCounts();

        Map<String, ? extends Number> totals();
    }

    /** What mansart-data-processor generates next to an interface: the bean class that implements it. */
    public abstract static class GadgetRepositoryImpl implements GadgetRepository {}

    /** Through {@code CrudRepository}. */
    @Repository
    public interface OrderRepository extends CrudRepository<Order, UUID> {
        List<Order> findByStatus(Status status);
    }

    /** An application's own generic base interface: the primary entity is bound one level down. */
    public interface NamedRepository<T> extends DataRepository<T, String> {}

    @Repository
    public interface CustomerRepository extends NamedRepository<Customer> {
        boolean existsByName(String name);
    }

    @Repository
    public interface BrokenRepository extends BasicRepository<Broken, Long> {}

    /** No Jakarta Data super-interface: no primary entity. */
    @Repository
    public interface ReportQueries {

        @Query("SELECT count(this) FROM Order")
        long orderCount();

        @Query("SELECT count(this) FROM Gadget")
        long gadgetCount();
    }

    /** The five repository interfaces, as the builder receives them. */
    static final List<Class<?>> REPOSITORIES = List.of(BrokenRepository.class, CustomerRepository.class,
            GadgetRepository.class, OrderRepository.class, ReportQueries.class);

    /** The models: Order's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        return type == Order.class ? orderModel() : io.vidocq.mansart.data.core.EntityModels.of(type);
    }

    /** The model of {@link Order}: its attributes in an order a model may hold them, id and version not first. */
    static EntityModel<Order> orderModel() {
        IdAttribute<Order, UUID> id = new IdAttribute<>("id", "id", UUID.class, Order.class, false, null, null);
        VersionAttribute<Order, Integer> version =
                new VersionAttribute<>("version", "row_version", Integer.class, Order.class, null, null);
        EnumAttribute<Order, Status> status = new EnumAttribute<>("status", "status", Status.class, Order.class,
                false, false, EnumStorage.STRING, null, null);
        ReferenceAttribute<Order, Customer> customer = new ReferenceAttribute<>("customer", "customer_id",
                Customer.class, Order.class, true, false, false, null, null);
        TextAttribute<Customer> customerName =
                new TextAttribute<>("name", "name", Customer.class, true, false, 100, null, null);
        JoinedAttribute<Order, String> joined = new JoinedAttribute<>(customerName,
                JoinPath.of(new JoinPath.Step("customer", "customer_id", "id", "customers", "", Customer.class)),
                Order.class);
        TextAttribute<Order> note = new TextAttribute<>("note", "note", Order.class, true, true, 500, null, null);
        List<Attribute<Order, ?>> attributes = List.of(note, id, customer, status, version, joined);
        Optional<VersionAttribute<Order, ?>> versioned = Optional.of(version);
        return new EntityModel<>(Order.class, "orders", "shop", id, versioned, attributes, null);
    }
}
