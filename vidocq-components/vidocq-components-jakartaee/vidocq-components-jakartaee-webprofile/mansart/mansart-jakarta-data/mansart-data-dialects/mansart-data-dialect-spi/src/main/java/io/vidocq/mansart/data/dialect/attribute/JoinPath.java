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
package io.vidocq.mansart.data.dialect.attribute;

import java.util.List;
import java.util.Objects;

/**
 * M8-3 — chain of {@code @ManyToOne}/{@code @OneToOne} relations to traverse to reach a leaf
 * attribute. Each {@link Step} names the relation attribute followed and the resulting target
 * entity type. The same {@code JoinPath} instance is reused across all leaf attributes that
 * share the same prefix, so dialects that walk a {@code Where} can deduplicate by identity.
 *
 * <p>Equality is structural (segment-by-segment). The dialect uses this to allocate a stable
 * alias per distinct path (e.g. {@code Book.author} → {@code t1}, {@code Book.publisher} →
 * {@code t2}, {@code Book.author.country} → {@code t3} chained on top of {@code t1}).
 */
public record JoinPath(List<Step> steps) {

    public JoinPath { steps = List.copyOf(steps); }

    public static JoinPath of(Step first, Step... rest) {
        var list = new java.util.ArrayList<Step>(rest.length + 1);
        list.add(first);
        java.util.Collections.addAll(list, rest);
        return new JoinPath(list);
    }

    public JoinPath append(Step extra) {
        var list = new java.util.ArrayList<Step>(steps.size() + 1);
        list.addAll(steps);
        list.add(extra);
        return new JoinPath(list);
    }

    /** Returns a fresh path containing only the {@code n} first segments. */
    public JoinPath prefix(int n) {
        if (n == steps.size()) return this;
        if (n <= 0 || n > steps.size()) throw new IllegalArgumentException("prefix " + n + " out of range");
        return new JoinPath(steps.subList(0, n));
    }

    /**
     * One hop in a join chain — names the relation followed and carries enough information for
     * the dialect to render {@code INNER JOIN target tN ON parent."fk" = tN."pk"} without any
     * extra registry lookup.
     *
     * @param relationName       attribute name on the source side (e.g. {@code "author"})
     * @param foreignKeyColumn   FK column on the source table (e.g. {@code "author_id"})
     * @param referencedColumn   PK column on the target table (e.g. {@code "id"})
     * @param targetTableName    quoted target table name (e.g. {@code "authors"})
     * @param targetSchemaName   target schema or {@code ""} if none
     * @param targetEntityType   target entity class (informational; for path equality + APT)
     */
    public record Step(String relationName,
                       String foreignKeyColumn,
                       String referencedColumn,
                       String targetTableName,
                       String targetSchemaName,
                       Class<?> targetEntityType) {
        public Step {
            Objects.requireNonNull(relationName);
            Objects.requireNonNull(foreignKeyColumn);
            Objects.requireNonNull(referencedColumn);
            Objects.requireNonNull(targetTableName);
            if (targetSchemaName == null) targetSchemaName = "";
            Objects.requireNonNull(targetEntityType);
        }
    }
}
