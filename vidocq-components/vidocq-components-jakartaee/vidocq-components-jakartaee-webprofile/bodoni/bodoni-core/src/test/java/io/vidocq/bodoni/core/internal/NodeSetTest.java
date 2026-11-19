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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

class NodeSetTest {

    private final Document document = XmlFactories.emptyDocument();
    private final Node a = document.createElement("a");
    private final Node b = document.createElement("b");
    private final Node c = document.createElement("c");

    @Test
    void isASnapshotInIterationOrder() {
        List<Node> source = new ArrayList<>(List.of(a, b, c));
        NodeSet set = NodeSet.of(source);
        source.clear();

        assertEquals(3, set.getLength());
        assertSame(a, set.item(0));
        assertSame(b, set.item(1));
        assertSame(c, set.item(2));
        assertEquals(List.of(a, b, c), set.nodes());
    }

    @Test
    void nodesCannotBeModified() {
        List<Node> nodes = NodeSet.of(List.of(a)).nodes();

        assertThrows(UnsupportedOperationException.class, () -> nodes.add(b));
    }

    @Test
    void itemOutOfRangeIsNull() {
        NodeSet set = NodeSet.of(List.of(a, b));

        assertNull(set.item(-1));
        assertNull(set.item(2));
        assertNull(NodeSet.of(List.of()).item(0));
    }
}
