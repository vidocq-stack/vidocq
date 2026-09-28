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
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the {@link MansartDataCatalogue} once per boot, from the repository interfaces the container holds and the
 * entity models Mansart itself uses. Best effort: a model that fails keeps its entity in the catalogue, with the class
 * of the exception and no columns; nothing is thrown for it.
 *
 * <p>Every repository is read and counted; only the lists stop at the limits.
 */
final class CatalogueBuilder {

    /** The limits of spec §3: 200 entities, 200 repositories, 200 methods per repository, 1,000 query characters. */
    static final CatalogueBuilder DEFAULT = new CatalogueBuilder(200, 200, 200, 1_000);

    /** The reason of a model that came back {@code null}, without id, or without table. */
    static final String UNUSABLE = "unusable model";

    private final int maxEntities;
    private final int maxRepositories;
    private final int maxMethods;
    private final int maxQuery;

    CatalogueBuilder(int maxEntities, int maxRepositories, int maxMethods, int maxQuery) {
        this.maxEntities = maxEntities;
        this.maxRepositories = maxRepositories;
        this.maxMethods = maxMethods;
        this.maxQuery = maxQuery;
    }

    /**
     * The {@code @Repository} interfaces among these bean classes and the interfaces they implement, each once — the
     * {@code *RepositoryImpl} bean mansart-data-processor generates counts as its interface — sorted by name.
     */
    static List<Class<?>> repositoryInterfaces(Iterable<Class<?>> beanClasses) {
        Set<Class<?>> found = new HashSet<>();
        for (Class<?> beanClass : beanClasses) {
            if (beanClass == null) {
                continue;
            }
            for (Class<?> itf : beanClass.getInterfaces()) {
                if (itf.isAnnotationPresent(jakarta.data.repository.Repository.class)) {
                    found.add(itf);
                    break;
                }
            }
            if (beanClass.isInterface() && beanClass.isAnnotationPresent(jakarta.data.repository.Repository.class)) {
                found.add(beanClass);
            }
        }
        return found.stream().sorted(Comparator.comparing(Class::getName)).toList();
    }

    /** The catalogue of these repository interfaces, their entities' models read through {@code models}. */
    MansartDataCatalogue build(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models) {
        Map<Class<?>, RepositoryReader.Read> reads = new LinkedHashMap<>();
        for (Class<?> repository : repositories) {
            reads.put(repository, RepositoryReader.read(repository, maxQuery));
        }
        List<Class<?>> entityTypes = reads.values().stream()
                .map(RepositoryReader.Read::entity)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Class<?>, String> entityNames = names(entityTypes);
        Map<Class<?>, String> repositoryNames = names(reads.keySet());
        int methodCount = reads.values().stream().mapToInt(read -> read.methods().size()).sum();

        List<MansartDataCatalogue.Entity> entities = entityTypes.stream()
                .sorted(Comparator.comparing(entityNames::get))
                .limit(maxEntities)
                .map(type -> entity(type, entityNames.get(type), models))
                .toList();

        List<Class<?>> ordered = new ArrayList<>(reads.keySet());
        ordered.sort(Comparator.comparing(repositoryNames::get));
        List<MansartDataCatalogue.Repository> listed = new ArrayList<>();
        for (Class<?> type : ordered.subList(0, Math.min(ordered.size(), maxRepositories))) {
            RepositoryReader.Read read = reads.get(type);
            List<MansartDataCatalogue.Method> methods = read.methods();
            Class<?> entity = read.entity();
            listed.add(new MansartDataCatalogue.Repository(repositoryNames.get(type), type.getName(),
                    entity == null ? null : entity.getName(), entity == null ? null : entityNames.get(entity),
                    read.idType(), methods.subList(0, Math.min(methods.size(), maxMethods)), methods.size(),
                    read.inherits()));
        }
        return new MansartDataCatalogue(entities, listed, entityTypes.size(), reads.size(), methodCount);
    }

    /** The name the catalogue gives each type: its simple name, its full name when another type shares it. */
    static Map<Class<?>, String> names(Collection<Class<?>> types) {
        Map<String, Integer> uses = new HashMap<>();
        for (Class<?> type : types) {
            uses.merge(type.getSimpleName(), 1, Integer::sum);
        }
        Map<Class<?>, String> names = new HashMap<>();
        for (Class<?> type : types) {
            String simple = type.getSimpleName();
            names.put(type, simple.isEmpty() || uses.get(simple) > 1 ? type.getName() : simple);
        }
        return names;
    }

    private static MansartDataCatalogue.Entity entity(Class<?> type, String name,
                                                      Function<Class<?>, EntityModel<?>> models) {
        EntityModel<?> model;
        try {
            model = models.apply(type);
        } catch (RuntimeException | LinkageError failure) {
            return failed(type, name, failure.getClass().getName());
        }
        if (model == null || model.id() == null || model.tableName() == null || model.tableName().isBlank()) {
            return failed(type, name, UNUSABLE);
        }
        try {
            return new MansartDataCatalogue.Entity(name, type.getName(), table(model), columns(model), null);
        } catch (RuntimeException | LinkageError failure) {
            return failed(type, name, failure.getClass().getName());
        }
    }

    private static MansartDataCatalogue.Entity failed(Class<?> type, String name, String failure) {
        return new MansartDataCatalogue.Entity(name, type.getName(), "", List.of(), failure);
    }

    private static String table(EntityModel<?> model) {
        String schema = model.schema();
        return schema == null || schema.isBlank() ? model.tableName() : schema + "." + model.tableName();
    }

    /** The id first, then the version, then the other attributes in model order. */
    private static <E> List<Column> columns(EntityModel<E> model) {
        IdAttribute<E, ?> id = model.id();
        VersionAttribute<E, ?> version = model.version() == null ? null : model.version().orElse(null);
        List<Column> columns = new ArrayList<>();
        columns.add(column(id));
        if (version != null) {
            columns.add(column(version));
        }
        for (Attribute<E, ?> attribute : model.attributes()) {
            if (attribute == null || attribute.name().equals(id.name())
                    || (version != null && attribute.name().equals(version.name()))) {
                continue;
            }
            columns.add(column(attribute));
        }
        return columns;
    }

    private static Column column(Attribute<?, ?> attribute) {
        Class<?> type = attribute.javaType();
        return new Column(text(attribute.name()), text(attribute.columnName()), type == null ? "" : TypeNames.of(type),
                key(attribute), attribute.nullable(), attribute.unique());
    }

    private static String key(Attribute<?, ?> attribute) {
        return switch (attribute) {
            case IdAttribute<?, ?> id -> id.generated() ? "id, generated" : "id";
            case VersionAttribute<?, ?> _ -> "version";
            case EnumAttribute<?, ?> _ -> "enum";
            case ReferenceAttribute<?, ?> reference -> "→ " + TypeNames.of(reference.javaType());
            case JoinedAttribute<?, ?> _ -> "joined";
            default -> "";
        };
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
