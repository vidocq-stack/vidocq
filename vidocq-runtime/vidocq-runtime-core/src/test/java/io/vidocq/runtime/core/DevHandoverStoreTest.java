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
package io.vidocq.runtime.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevHandoverStoreTest {

    private static final String KEY = "servlet:sessions:/";

    private final DevHandoverStore store = new DevHandoverStore();

    @Test
    void putOutsideAnExportThrows() {
        byte[] bytes = {1};

        assertThrows(IllegalStateException.class, () -> store.put(KEY, bytes));
    }

    @Test
    void askedIsTrueOnceABootAskedForAKeyEvenAMissingOne() {
        assertFalse(store.asked());

        store.take("servlet:sessions:/");

        assertTrue(store.asked());
    }

    @Test
    void takeRemovesTheKey() {
        store.beginExport();
        store.put(KEY, new byte[] {1, 2});
        store.endExport();

        assertArrayEquals(new byte[] {1, 2}, store.take(KEY).orElseThrow());
        assertEquals(Optional.empty(), store.take(KEY));
    }

    @Test
    void clearDropsAndNamesTheRest() {
        store.beginExport();
        store.put(KEY, new byte[] {1});
        store.put("servlet:sessions:/admin", new byte[] {2});
        store.endExport();
        store.take(KEY);

        assertEquals(Set.of("servlet:sessions:/admin"), store.clear());
        assertEquals(Optional.empty(), store.take("servlet:sessions:/admin"));
        assertEquals(Set.of(), store.clear());
    }

    @Test
    void exportingIsTrueOnlyBetweenBeginAndEnd() {
        assertFalse(store.exporting());
        store.beginExport();
        assertTrue(store.exporting());
        store.endExport();
        assertFalse(store.exporting());
    }

    @Test
    void putCopiesTheBytes() {
        byte[] bytes = {1, 2, 3};
        store.beginExport();
        store.put(KEY, bytes);
        store.endExport();

        bytes[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, store.take(KEY).orElseThrow());
    }

    @Test
    void aSecondPutOfAKeyReplacesIt() {
        store.beginExport();
        store.put(KEY, new byte[] {1});
        store.put(KEY, new byte[] {2});
        store.endExport();

        assertArrayEquals(new byte[] {2}, store.take(KEY).orElseThrow());
    }
}
