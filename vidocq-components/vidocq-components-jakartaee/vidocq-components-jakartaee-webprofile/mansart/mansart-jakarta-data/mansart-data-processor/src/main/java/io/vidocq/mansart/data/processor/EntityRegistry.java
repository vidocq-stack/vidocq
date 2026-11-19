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
package io.vidocq.mansart.data.processor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * M8-3i — cross-entity descriptor cache populated during the APT entity scan and consumed by
 * {@link RepositoryWriter} when parsing query-by-method-name expressions. Allows splitting
 * {@code findByAuthorName} into the path {@code "author.name"} when {@code author} is a known
 * relation pointing at an entity that has a {@code name} attribute.
 *
 * <p>Populated incrementally — entries from previous APT rounds in the same compilation persist
 * here so a repository in round N can reference an entity processed in round N−1.
 *
 * <p>Not thread-safe; APT runs single-threaded per compilation unit.
 */
final class EntityRegistry {

    /** Per-entity facet: flat attributes (by name) and relation map (relation → target entity FQN). */
    record EntityFacet(Set<String> attrs, Map<String, String> relations) {
        EntityFacet { attrs = Set.copyOf(attrs); relations = Map.copyOf(relations); }
    }

    private final Map<String, EntityFacet> byFqn = new LinkedHashMap<>();

    void register(String entityFqn, EntityScanner.EntityDescriptor d) {
        Set<String> attrs = new LinkedHashSet<>();
        Map<String, String> rels = new LinkedHashMap<>();
        if (d.id() != null)      attrs.add(d.id().name());
        if (d.version() != null) attrs.add(d.version().name());
        for (var a : d.attributes()) {
            attrs.add(a.name());
            if (a.kind() == EntityScanner.AttributeKind.REFERENCE) {
                rels.put(a.name(), a.javaTypeFqn());
            }
        }
        byFqn.put(entityFqn, new EntityFacet(attrs, rels));
    }

    EntityFacet get(String entityFqn) { return byFqn.get(entityFqn); }
}
