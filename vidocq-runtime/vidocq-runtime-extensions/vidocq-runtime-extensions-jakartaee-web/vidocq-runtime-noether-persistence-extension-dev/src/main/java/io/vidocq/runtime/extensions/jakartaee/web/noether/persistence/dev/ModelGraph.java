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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Embeddable;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Edge;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Fact;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Node;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Model tab's graph of a unit, read from its {@link UnitDescription}: a box per entity, embeddable and mapped
 * superclass, an arrow from a subclass to what it extends, a line per relation from its owning side, labelled with its
 * attribute, its cardinality and its inverse attribute, and a dashed line to an embedded value. A box's facts are its
 * table and the attributes its class declares, with their columns: an inherited attribute is listed on the box of the
 * class that declares it.
 */
final class ModelGraph {

    static final String ENTITY = "entity:";
    static final String EMBEDDABLE = "embeddable:";
    static final String CLASS = "class:";
    private static final String EXTENDS = "extends";

    /** The cardinality of each kind of relation, as its line says it. */
    private static final Map<Kind, String> CARDINALITIES = new EnumMap<>(Map.of(
            Kind.MANY_TO_ONE, "*..1", Kind.ONE_TO_ONE, "1..1", Kind.ONE_TO_MANY, "1..*", Kind.MANY_TO_MANY, "*..*"));

    private ModelGraph() {
    }

    /**
     * The graph of {@code unit}, or an error when it has more boxes or lines than a graph draws.
     *
     * @param unit the unit
     * @return a {@link ActionResult#GRAPH} result, its summary counting entities and relations
     */
    static ActionResult of(UnitDescription unit) {
        List<Node> nodes;
        List<Edge> edges;
        try {
            nodes = nodes(unit);
            edges = edges(unit);
        } catch (IllegalArgumentException refused) {
            return failure("the model cannot be drawn: " + refused.getMessage());
        }
        if (nodes.size() > ActionResult.MAX_NODES || edges.size() > ActionResult.MAX_EDGES) {
            return failure(nodes.size() + " boxes and " + edges.size() + " lines: past the " + ActionResult.MAX_NODES
                    + " boxes and " + ActionResult.MAX_EDGES + " lines a graph draws");
        }
        long relations = edges.stream().filter(e -> !e.arrow() && !e.dashed()).count();
        String summary = count(unit.entities().size(), "entity", "entities") + ", "
                + count(relations, "relation", "relations");
        try {
            return ActionResult.ofGraph(summary, nodes, edges);
        } catch (IllegalArgumentException refused) {
            return failure("the model cannot be drawn: " + refused.getMessage());
        }
    }

    /** The boxes: the entities, the embeddables, then the mapped superclasses, each in the unit's order. */
    static List<Node> nodes(UnitDescription unit) {
        List<Node> nodes = new ArrayList<>();
        for (Entity entity : unit.entities()) {
            List<Fact> facts = new ArrayList<>();
            facts.add(new Fact("table", Objects.requireNonNullElse(entity.table(), "none")));
            if (entity.inheritance() != null && entity.inheritance() != Inheritance.NONE) {
                facts.add(new Fact("inheritance", entity.inheritance().name()));
            }
            entity.attributes().stream().filter(a -> entity.className().equals(a.declaringClass()))
                    .map(ModelGraph::fact).forEach(facts::add);
            nodes.add(new Node(ENTITY + entity.name(), entity.name(),
                    entity.isAbstract() ? "abstract entity" : "entity", facts));
        }
        for (Embeddable embeddable : unit.embeddables()) {
            nodes.add(new Node(EMBEDDABLE + embeddable.className(), simpleName(embeddable.className()), "embeddable",
                    embeddable.attributes().stream().map(ModelGraph::fact).toList()));
        }
        mappedSuperclasses(unit).forEach((className, attributes) -> nodes.add(new Node(CLASS + className,
                simpleName(className), "mapped superclass", attributes.stream().map(ModelGraph::fact).toList())));
        return nodes;
    }

    /**
     * The lines: an {@code extends} arrow from each entity to its parent entity and to each mapped superclass that
     * declares its own attributes, then, from the class that declares them, a line per owning relation to an entity
     * of the unit and a dashed line per embedded value.
     */
    static List<Edge> edges(UnitDescription unit) {
        Map<String, Entity> byName = unit.entities().stream()
                .collect(Collectors.toMap(Entity::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<String> embeddables = unit.embeddables().stream().map(Embeddable::className)
                .collect(Collectors.toSet());
        Map<String, List<Attribute>> mapped = mappedSuperclasses(unit);
        List<Edge> edges = new ArrayList<>();
        for (Entity entity : unit.entities()) {
            Entity parent = entity.superEntity() == null ? null : byName.get(entity.superEntity());
            if (parent != null) {
                edges.add(new Edge(ENTITY + entity.name(), ENTITY + parent.name(), EXTENDS, true, false));
            }
            superclassesOf(entity, parent, mapped).forEach(className ->
                    edges.add(new Edge(ENTITY + entity.name(), CLASS + className, EXTENDS, true, false)));
        }
        for (Entity entity : unit.entities()) {
            for (Attribute attribute : entity.attributes()) {
                if (entity.className().equals(attribute.declaringClass())) {
                    addLine(edges, ENTITY + entity.name(), entity.name(), attribute, byName, embeddables);
                }
            }
        }
        mapped.forEach((className, attributes) -> attributes.forEach(attribute ->
                addLine(edges, CLASS + className, null, attribute, byName, embeddables)));
        for (Embeddable embeddable : unit.embeddables()) {
            embeddable.attributes().forEach(attribute ->
                    addLine(edges, EMBEDDABLE + embeddable.className(), null, attribute, byName, embeddables));
        }
        return edges;
    }

    /**
     * The mapped superclasses of the unit, by class name, each with the attributes it declares: a class that declares
     * an attribute of an entity, and is neither an entity nor an embeddable of the unit.
     */
    private static Map<String, List<Attribute>> mappedSuperclasses(UnitDescription unit) {
        Set<String> known = new LinkedHashSet<>();
        unit.entities().forEach(e -> known.add(e.className()));
        unit.embeddables().forEach(e -> known.add(e.className()));
        Map<String, List<Attribute>> mapped = new LinkedHashMap<>();
        for (Entity entity : unit.entities()) {
            for (Attribute attribute : entity.attributes()) {
                String declaring = attribute.declaringClass();
                if (declaring != null && !known.contains(declaring)) {
                    List<Attribute> declared = mapped.computeIfAbsent(declaring, k -> new ArrayList<>());
                    if (declared.stream().noneMatch(a -> a.name().equals(attribute.name()))) {
                        declared.add(attribute);
                    }
                }
            }
        }
        return mapped;
    }

    /** The mapped superclasses declaring attributes of {@code entity} that its parent entity does not have. */
    private static Set<String> superclassesOf(Entity entity, Entity parent, Map<String, List<Attribute>> mapped) {
        Set<String> inherited = new LinkedHashSet<>();
        if (parent != null) {
            parent.attributes().forEach(a -> inherited.add(a.declaringClass()));
        }
        Set<String> classes = new LinkedHashSet<>();
        for (Attribute attribute : entity.attributes()) {
            String declaring = attribute.declaringClass();
            if (mapped.containsKey(declaring) && !inherited.contains(declaring)) {
                classes.add(declaring);
            }
        }
        return classes;
    }

    /**
     * Adds the line of {@code attribute}, declared by the box {@code from}: an owning relation to an entity of the
     * unit, or an embedded value of one of its embeddables. {@code owner} is the entity name of {@code from}, which
     * the inverse attribute targets; {@code null} for a mapped superclass or an embeddable.
     */
    private static void addLine(List<Edge> edges, String from, String owner, Attribute attribute,
                                Map<String, Entity> byName, Set<String> embeddables) {
        String cardinality = CARDINALITIES.get(attribute.kind());
        if (cardinality != null) {
            Entity target = byName.get(attribute.target());
            if (target != null && attribute.owning()) {
                String inverse = target.attributes().stream()
                        .filter(i -> attribute.name().equals(i.mappedBy()) && (owner == null || owner.equals(i.target())))
                        .map(i -> " / " + i.name()).findFirst().orElse("");
                edges.add(new Edge(from, ENTITY + target.name(), attribute.name() + " " + cardinality + inverse,
                        false, false));
            }
        } else if (embeddables.contains(attribute.target())) {
            edges.add(new Edge(from, EMBEDDABLE + attribute.target(), attribute.name(), false, true));
        }
    }

    /**
     * An attribute as a fact: its type's simple name, its kind, its columns, then for a relation its side and its
     * join table.
     */
    static Fact fact(Attribute attribute) {
        List<String> parts = new ArrayList<>();
        parts.add(simpleName(attribute.javaType()));
        parts.add(attribute.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (!attribute.columns().isEmpty()) {
            parts.add(String.join(" ", attribute.columns()));
        }
        if (CARDINALITIES.containsKey(attribute.kind())) {
            parts.add(attribute.owning() ? "owner" : "mapped by " + attribute.mappedBy());
        }
        if (attribute.joinTable() != null) {
            parts.add("join table " + attribute.joinTable());
        }
        String value = String.join(", ", parts);
        return new Fact(attribute.name(), value.length() > PanelAction.MAX_DESCRIPTION
                ? value.substring(0, PanelAction.MAX_DESCRIPTION - 1) + "…" : value);
    }

    /** {@code com.acme.Note} as {@code Note}; {@code null} as an empty name. */
    static String simpleName(String className) {
        return className == null ? "" : className.substring(className.lastIndexOf('.') + 1);
    }

    private static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static ActionResult failure(String summary) {
        return new ActionResult(summary, null, null, true, null);
    }
}
