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
package io.vidocq.grimm.internal.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The collection helpers behind the copy-on-write model objects. */
class ModelCollectionsTest {

    @Test
    void ensureMutableMapReusesALinkedHashMapAndCopiesAnyOtherMap() {
        LinkedHashMap<String, Integer> linked = new LinkedHashMap<>(Map.of("a", 1));
        Map<String, Integer> immutable = Map.of("b", 2);

        assertSame(linked, ModelCollections.ensureMutableMap(linked));
        Map<String, Integer> copy = ModelCollections.ensureMutableMap(immutable);
        assertNotSame(immutable, copy);
        copy.put("c", 3);
        assertEquals(2, copy.size());
        assertTrue(ModelCollections.ensureMutableMap(null).isEmpty());
    }

    @Test
    void ensureMutableListReusesAnArrayListAndCopiesAnyOtherList() {
        ArrayList<String> array = new ArrayList<>(List.of("a"));
        List<String> immutable = List.of("b");

        assertSame(array, ModelCollections.ensureMutableList(array));
        List<String> copy = ModelCollections.ensureMutableList(immutable);
        copy.add("c");
        assertEquals(List.of("b", "c"), copy);
        assertTrue(ModelCollections.ensureMutableList(null).isEmpty());
    }

    @Test
    void viewsAreNullForNullAndUnmodifiableOtherwise() {
        assertNull(ModelCollections.immutableMapView(null));
        assertNull(ModelCollections.immutableListView(null));
        assertNull(ModelCollections.mutableMap(null));
        assertNull(ModelCollections.mutableList(null));
        Map<String, Integer> view = ModelCollections.immutableMapView(Map.of("a", 1));
        assertThrows(UnsupportedOperationException.class, view::clear);
    }
}
