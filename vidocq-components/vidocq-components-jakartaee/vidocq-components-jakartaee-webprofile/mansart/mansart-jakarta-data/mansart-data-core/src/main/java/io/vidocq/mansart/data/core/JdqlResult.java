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
package io.vidocq.mansart.data.core;

import java.util.List;

/**
 * What {@link JdqlExecutor#run} returns for one JDQL statement, by the statement's shape. Every list is read whole,
 * as every Mansart query is: the caller cuts.
 */
public sealed interface JdqlResult {

    /**
     * A plain {@code FROM … [WHERE …] [ORDER BY …]}.
     *
     * @param entities the entities, in the statement's order
     */
    record Entities(List<?> entities) implements JdqlResult {

        public Entities {
            entities = List.copyOf(entities);
        }
    }

    /**
     * A projection, {@code SELECT a, b FROM …}, or {@code SELECT a FROM …}.
     *
     * @param columns the attributes selected, as the statement names them, a path such as {@code author.name}
     *                included
     * @param rows    one array per row, a value per column in that order
     */
    record Rows(List<String> columns, List<Object[]> rows) implements JdqlResult {

        public Rows {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /**
     * {@code SELECT COUNT(this) FROM …}: the rows counted; an {@code UPDATE} or a {@code DELETE}: the rows changed.
     *
     * @param count how many
     */
    record Count(long count) implements JdqlResult {}

    /**
     * An aggregate, {@code SELECT MAX(price) FROM …}.
     *
     * @param value its value, {@code null} when there is no row
     */
    record Value(Object value) implements JdqlResult {}
}
