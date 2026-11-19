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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.el.ELProcessor;
import org.junit.jupiter.api.Test;

class CollectionConstructionTest {
    @Test
    void constructsNestedListsSetsAndMapsIncludingNullsAndDuplicateKeys() {
        var el = new ELProcessor();
        assertEquals(List.of(1L, 5L), el.eval("[1, 2+3]"));
        assertEquals(Set.of(1L, 2L), el.eval("{1,2,1}"));
        assertEquals(Set.of(), el.eval("{}"));
        assertEquals(Map.of("a", List.of(1L,2L), "b", 4L), el.eval("{'a':[1,2],'b':3,'b':4}"));
        List<?> list = el.eval("[null,1]");
        assertNull(list.getFirst());
        Map<?, ?> map = el.eval("{null:1,'a':null}");
        assertEquals(1L, map.get(null));
        assertTrue(map.containsKey("a"));
    }

    @Test
    void evaluatesConstructionElementsInOrderAndReturnsFreshMutableCollections() {
        var el = new ELProcessor();
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        el.defineBean("counter", counter);
        assertEquals(List.of(0,1), el.eval("[counter.getAndIncrement(),counter.getAndIncrement()]"));
        List<Object> first = el.eval("[1,2]");
        List<Object> second = el.eval("[1,2]");
        first.add(3L);
        assertEquals(2, second.size());
        assertEquals(42L, el.<Object>eval("list = [1,2]; list[0] = 42; list[0]"));
    }
}

