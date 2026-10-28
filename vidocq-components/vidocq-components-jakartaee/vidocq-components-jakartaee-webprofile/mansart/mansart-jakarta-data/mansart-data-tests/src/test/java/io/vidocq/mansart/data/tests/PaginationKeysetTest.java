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
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.dialect.OrderBy;
import io.vidocq.mansart.data.dialect.Pagination;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MANSART-011: {@link Pagination.Keyset} is a value, whatever array instance holds its cursor. */
class PaginationKeysetTest {

    @Test
    void keysetsWithEqualCursorsAreEqual() {
        var keyset = new Pagination.Keyset(10, new Object[]{42L, "b"}, OrderBy.NONE);
        var same = new Pagination.Keyset(10, new Object[]{42L, "b"}, OrderBy.NONE);

        assertThat(same).isEqualTo(keyset).hasSameHashCodeAs(keyset);
        assertThat(keyset).hasToString("Keyset[limit=10, cursor=[42, b], order=" + OrderBy.NONE + "]");
    }

    @Test
    void keysetsDifferingInAnyComponentAreNotEqual() {
        var keyset = new Pagination.Keyset(10, new Object[]{42L}, OrderBy.NONE);

        assertThat(new Pagination.Keyset(11, new Object[]{42L}, OrderBy.NONE)).isNotEqualTo(keyset);
        assertThat(new Pagination.Keyset(10, new Object[]{43L}, OrderBy.NONE)).isNotEqualTo(keyset);
        assertThat(new Pagination.Keyset(10, new Object[]{42L}, new OrderBy(java.util.List.of())))
                .isEqualTo(keyset);
        assertThat(keyset).isNotEqualTo(Pagination.NONE);
    }
}
