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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Entities and repositories the run actions are tested with, as an application declares them. Gizmo and Broken go
 * through Mansart's own {@code EntityModels.of} (no metamodel is generated here, so Mansart builds their model at
 * run time); the model of Part is written by hand, to hold a reference and a joined attribute.
 */
final class RunFixtures {

    private RunFixtures() {}

    public enum Level { LOW, HIGH }

    /** Its id is the field named {@code id}; one field of most kinds, a primitive among them. */
    public static class Gizmo {
        private Long id;
        private String name;
        private int stock;
        private Level level;
        private LocalDate due;
        private BigDecimal price;

        public Gizmo() {}

        Long id() {
            return id;
        }

        String name() {
            return name;
        }

        int stock() {
            return stock;
        }

        Level level() {
            return level;
        }

        LocalDate due() {
            return due;
        }

        BigDecimal price() {
            return price;
        }
    }

    /** Its model is {@link #partModel()}: a reference to a gizmo, and the gizmo's name joined. */
    public static class Part {
        private Long id;
        private Gizmo gizmo;
        private String label;

        public Part() {}

        Gizmo gizmo() {
            return gizmo;
        }

        String label() {
            return label;
        }
    }

    /** No id field: Mansart cannot build its model. */
    public static class Broken {
        private String label;

        public Broken() {}
    }

    static Gizmo gizmo(Long id, String name, int stock, Level level, LocalDate due, BigDecimal price) {
        Gizmo gizmo = new Gizmo();
        gizmo.id = id;
        gizmo.name = name;
        gizmo.stock = stock;
        gizmo.level = level;
        gizmo.due = due;
        gizmo.price = price;
        return gizmo;
    }

    static Part part(Long id, Gizmo gizmo, String label) {
        Part part = new Part();
        part.id = id;
        part.gizmo = gizmo;
        part.label = label;
        return part;
    }

    /** Every kind of method: reads, writes of each rule, overloads, and parameters §4 does not support. */
    @Repository
    public interface GizmoRepository extends BasicRepository<Gizmo, Long> {

        List<Gizmo> findByName(@Param("name") String name);

        long countByStockGreaterThan(@Param("min") int min);

        @Query("FROM Gizmo WHERE name LIKE :pattern")
        List<Gizmo> search(@Param("pattern") String pattern);

        @Query("FROM Gizmo WHERE name LIKE :pattern AND stock > :min")
        List<Gizmo> search(@Param("pattern") String pattern, @Param("min") int min);

        @Query("  update Gizmo SET stock = 0 WHERE name = :name")
        long empty(@Param("name") String name);

        @Query("DELETE FROM Gizmo WHERE stock = 0")
        long purge();

        long deleteByName(@Param("name") String name);

        @Insert
        Gizmo add(@Param("gizmo") Gizmo gizmo);

        @Update
        Gizmo change(@Param("gizmo") Gizmo gizmo);

        @Delete
        void remove(@Param("gizmo") Gizmo gizmo);

        @Save
        Gizmo keep(@Param("gizmo") Gizmo gizmo);

        Page<Gizmo> findByLevel(@Param("level") Level level, @Param("page") PageRequest page);

        List<Gizmo> findByNameIn(@Param("names") List<String> names);
    }

    @Repository
    public interface PartRepository extends BasicRepository<Part, Long> {
        List<Part> findByLabel(@Param("label") String label);
    }

    @Repository
    public interface BrokenRepository extends BasicRepository<Broken, Long> {}

    /** No Jakarta Data super-interface: no primary entity, no inherited method. */
    @Repository
    public interface ReportQueries {
        @Query("SELECT count(this) FROM Gizmo")
        long gizmoCount();
    }

    /** The entities of the catalogue, by class name. */
    static final Set<String> ENTITIES = Set.of(Gizmo.class.getName(), Part.class.getName(), Broken.class.getName());

    /** The repositories the panel runs, as MansartDataLive publishes them. */
    static final List<Class<?>> REPOSITORIES = List.of(GizmoRepository.class, PartRepository.class,
            ReportQueries.class);

    /** The models: Part's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        return type == Part.class ? partModel() : EntityModels.of(type);
    }

    static EntityJson entities() {
        return new EntityJson(RunFixtures::model, ENTITIES);
    }

    /** The catalogue of {@link #REPOSITORIES}; only the names and classes matter to the actions. */
    static MansartDataCatalogue catalogue() {
        MansartDataCatalogue.Entity gizmo =
                new MansartDataCatalogue.Entity("Gizmo", Gizmo.class.getName(), "gizmos", List.of(), null);
        MansartDataCatalogue.Entity part =
                new MansartDataCatalogue.Entity("Part", Part.class.getName(), "parts", List.of(), null);
        MansartDataCatalogue.Repository gizmos = new MansartDataCatalogue.Repository("GizmoRepository",
                GizmoRepository.class.getName(), Gizmo.class.getName(), "Gizmo", "Long", List.of(), 13, "");
        MansartDataCatalogue.Repository parts = new MansartDataCatalogue.Repository("PartRepository",
                PartRepository.class.getName(), Part.class.getName(), "Part", "Long", List.of(), 1, "");
        MansartDataCatalogue.Repository reports = new MansartDataCatalogue.Repository("ReportQueries",
                ReportQueries.class.getName(), null, null, null, List.of(), 1, "");
        return new MansartDataCatalogue(List.of(gizmo, part), List.of(gizmos, parts, reports), 2, 3, 15);
    }

    /** The model of {@link Part}, with real handles: its id, a reference to a gizmo, a label, a joined name. */
    static EntityModel<Part> partModel() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            IdAttribute<Part, Long> id = new IdAttribute<>("id", "id", Long.class, Part.class, true,
                    lookup.findGetter(Part.class, "id", Long.class), lookup.findSetter(Part.class, "id", Long.class));
            ReferenceAttribute<Part, Gizmo> gizmo = new ReferenceAttribute<>("gizmo", "gizmo_id", Gizmo.class,
                    Part.class, true, false, false, lookup.findGetter(Part.class, "gizmo", Gizmo.class),
                    lookup.findSetter(Part.class, "gizmo", Gizmo.class));
            TextAttribute<Part> label = new TextAttribute<>("label", "label", Part.class, true, false, 100,
                    lookup.findGetter(Part.class, "label", String.class),
                    lookup.findSetter(Part.class, "label", String.class));
            TextAttribute<Gizmo> gizmoName = new TextAttribute<>("name", "name", Gizmo.class, true, false, 100,
                    null, null);
            JoinedAttribute<Part, String> joined = new JoinedAttribute<>(gizmoName,
                    JoinPath.of(new JoinPath.Step("gizmo", "gizmo_id", "id", "gizmos", "", Gizmo.class)), Part.class);
            return new EntityModel<>(Part.class, "parts", "", id, Optional.empty(), List.of(id, gizmo, label, joined),
                    lookup.findConstructor(Part.class, MethodType.methodType(void.class)));
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
