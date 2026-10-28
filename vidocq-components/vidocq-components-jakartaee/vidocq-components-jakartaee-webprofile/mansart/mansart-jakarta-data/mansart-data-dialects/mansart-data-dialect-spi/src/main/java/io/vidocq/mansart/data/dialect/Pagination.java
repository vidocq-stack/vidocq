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

import java.util.Arrays;
import java.util.Objects;

public sealed interface Pagination {

    Pagination NONE = new None();

    record None() implements Pagination {}
    record Offset(long offset, int limit) implements Pagination {}
    record Keyset(int limit, Object[] cursor, OrderBy order) implements Pagination {

        /** By content (MANSART-011): the record default would compare the cursor arrays by identity. */
        @Override
        public boolean equals(Object o) {
            return o instanceof Keyset k
                    && limit == k.limit
                    && Arrays.equals(cursor, k.cursor)
                    && Objects.equals(order, k.order);
        }

        @Override
        public int hashCode() {
            return Objects.hash(limit, Arrays.hashCode(cursor), order);
        }

        @Override
        public String toString() {
            return "Keyset[limit=" + limit + ", cursor=" + Arrays.toString(cursor) + ", order=" + order + "]";
        }
    }
}
