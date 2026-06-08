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
package io.vidocq.mansart.data.dialect;

import java.util.List;

/**
 * Rendered SQL fragment with the ordered list of bind sites it expects.
 *
 * <p>{@link #binds()} is a flat list of {@link BindSite} entries describing the order in which
 * arguments must be set on the {@link java.sql.PreparedStatement}. Composite predicates (e.g.
 * {@link Where.Between}, {@link Where.In}) expand to multiple bind sites at render time.
 */
public record SqlFragment(String sql, List<BindSite> binds) {

    public SqlFragment { binds = List.copyOf(binds); }

    /** Describes one parameter slot in the rendered SQL. */
    public record BindSite(Attribute<?, ?> target, BindKind kind) {
        public enum BindKind { VALUE, RANGE_LOW, RANGE_HIGH, IN_ELEMENT, KEYSET, LIMIT, OFFSET }
    }
}
