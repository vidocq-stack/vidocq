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

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * An immutable XPath node-set, the Java type of a node-set result (Tags 3.0 section 11.1.4)
 * that {@code x:set} exports. Being a {@link NodeList}, the JDK's XPath takes it back as a
 * node-set when a later expression references it (section 11.1.3).
 */
public final class NodeSet implements NodeList {

    private final List<Node> nodes;

    private NodeSet(List<Node> nodes) {
        this.nodes = nodes;
    }

    /**
     * A snapshot of {@code nodes}, in iteration order.
     *
     * @param nodes the nodes, none {@code null}
     * @return the node-set
     */
    public static NodeSet of(Iterable<Node> nodes) {
        List<Node> copy = new ArrayList<>();
        nodes.forEach(copy::add);
        return new NodeSet(List.copyOf(copy));
    }

    /**
     * The nodes, in order.
     *
     * @return an unmodifiable list
     */
    public List<Node> nodes() {
        return nodes;
    }

    @Override
    public Node item(int index) {
        return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
    }

    @Override
    public int getLength() {
        return nodes.size();
    }
}
