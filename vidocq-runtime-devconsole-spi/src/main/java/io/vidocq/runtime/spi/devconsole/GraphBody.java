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
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Edge;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Fact;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Node;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The body of an {@link ActionResult#GRAPH} result, written by hand: this module depends on no JSON library. A graph
 * is never cut, since half of one means nothing: a body past {@link ActionResult#MAX_CONTENT} characters is refused.
 */
final class GraphBody {

    private GraphBody() {
    }

    static String write(List<Node> nodes, List<Edge> edges) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        if (nodes.size() > ActionResult.MAX_NODES || edges.size() > ActionResult.MAX_EDGES) {
            throw new IllegalArgumentException("a graph has at most " + ActionResult.MAX_NODES + " nodes and "
                    + ActionResult.MAX_EDGES + " edges");
        }
        Set<String> ids = new HashSet<>();
        StringBuilder out = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            Node node = Objects.requireNonNull(nodes.get(i), "node");
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("node '" + node.id() + "' is in the graph twice");
            }
            out.append(i == 0 ? "{\"id\":" : ",{\"id\":");
            JsonText.quote(node.id(), out);
            out.append(",\"label\":");
            JsonText.quote(node.label(), out);
            out.append(",\"kind\":");
            JsonText.quote(node.kind(), out);
            out.append(",\"facts\":[");
            for (int f = 0; f < node.facts().size(); f++) {
                Fact fact = node.facts().get(f);
                out.append(f == 0 ? "[" : ",[");
                JsonText.quote(fact.name(), out);
                out.append(',');
                JsonText.quote(fact.value(), out);
                out.append(']');
            }
            out.append("]}");
        }
        out.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = Objects.requireNonNull(edges.get(i), "edge");
            for (String end : List.of(edge.from(), edge.to())) {
                if (!ids.contains(end)) {
                    throw new IllegalArgumentException("edge " + edge.from() + " -> " + edge.to() + " names node '"
                            + end + "', which is not in the graph");
                }
            }
            out.append(i == 0 ? "{\"from\":" : ",{\"from\":");
            JsonText.quote(edge.from(), out);
            out.append(",\"to\":");
            JsonText.quote(edge.to(), out);
            out.append(",\"label\":");
            JsonText.quote(edge.label(), out);
            out.append(",\"arrow\":").append(edge.arrow()).append(",\"dashed\":").append(edge.dashed()).append('}');
        }
        out.append("]}");
        if (out.length() > ActionResult.MAX_CONTENT) {
            throw new IllegalArgumentException("the graph passes " + ActionResult.MAX_CONTENT + " characters: "
                    + out.length());
        }
        return out.toString();
    }
}
