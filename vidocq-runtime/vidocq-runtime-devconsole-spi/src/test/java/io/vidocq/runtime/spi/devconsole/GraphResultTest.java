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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A graph an action answers with, such as the entities of a persistence unit and their relations. */
class GraphResultTest {

    private static final Node NOTE = new Node("Note", "Note", "entity",
            List.of(new Fact("table", "NOTE"), new Fact("title", "TITLE \"short\"\nVARCHAR(200)")));
    private static final Node AUTHOR = new Node("Author", "Author", "", List.of());

    @Test
    void aGraphWritesItsNodesAndEdgesInOrder() {
        ActionResult result = ActionResult.ofGraph("2 entities", List.of(NOTE, AUTHOR),
                List.of(new Edge("Note", "Author", "author *..1", true, false)));

        assertEquals("{\"nodes\":[{\"id\":\"Note\",\"label\":\"Note\",\"kind\":\"entity\",\"facts\":[[\"table\","
                + "\"NOTE\"],[\"title\",\"TITLE \\\"short\\\"\\nVARCHAR(200)\"]]},{\"id\":\"Author\",\"label\":"
                + "\"Author\",\"kind\":\"\",\"facts\":[]}],\"edges\":[{\"from\":\"Note\",\"to\":\"Author\",\"label\":"
                + "\"author *..1\",\"arrow\":true,\"dashed\":false}]}", result.body());
        assertEquals("2 entities", result.summary());
    }

    @Test
    void theContentTypeIsGraph() {
        assertEquals("application/x-graph+json", ActionResult.GRAPH);
        assertEquals(ActionResult.GRAPH, ActionResult.ofGraph(null, List.of(AUTHOR), List.of()).contentType());
        assertEquals(ActionResult.GRAPH, new ActionResult("ok", ActionResult.GRAPH, "{}", false, null).contentType(),
                "a graph is one of the content types a result takes");
    }

    @Test
    void anEdgeToAnUnknownNodeIsRefused() {
        List<Node> nodes = List.of(NOTE);
        List<Edge> edges = List.of(new Edge("Note", "Author", "", true, false));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ActionResult.ofGraph(null, nodes, edges));

        assertTrue(refused.getMessage().contains("Author"), refused.getMessage());
    }

    @Test
    void aNodeIdSeenTwiceIsRefused() {
        List<Node> twice = List.of(NOTE, NOTE);
        List<Edge> none = List.of();

        assertThrows(IllegalArgumentException.class, () -> ActionResult.ofGraph(null, twice, none));
    }

    @Test
    void pastFiveHundredNodesIsRefused() {
        List<Node> nodes = IntStream.rangeClosed(0, ActionResult.MAX_NODES)
                .mapToObj(i -> new Node("n" + i, "n" + i, "", List.of())).toList();
        List<Edge> none = List.of();

        assertEquals(500, ActionResult.MAX_NODES);
        assertThrows(IllegalArgumentException.class, () -> ActionResult.ofGraph(null, nodes, none));
        assertEquals(ActionResult.GRAPH,
                ActionResult.ofGraph(null, nodes.subList(0, ActionResult.MAX_NODES), none).contentType());
    }

    @Test
    void pastTwoThousandEdgesIsRefused() {
        List<Edge> edges = IntStream.rangeClosed(0, ActionResult.MAX_EDGES)
                .mapToObj(i -> new Edge("Note", "Author", "", false, false)).toList();

        List<Node> nodes = List.of(NOTE, AUTHOR);

        assertEquals(2000, ActionResult.MAX_EDGES);
        assertThrows(IllegalArgumentException.class, () -> ActionResult.ofGraph(null, nodes, edges));
    }

    @Test
    void aGraphPastTheContentLimitIsRefused() {
        Fact big = new Fact("comment", "x".repeat(PanelAction.MAX_DESCRIPTION));
        List<Node> nodes = IntStream.range(0, 140)
                .mapToObj(i -> new Node("n" + i, "n" + i, "", List.of(big))).toList();
        List<Edge> none = List.of();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ActionResult.ofGraph(null, nodes, none));
        assertTrue(refused.getMessage().contains(String.valueOf(ActionResult.MAX_CONTENT)), refused.getMessage());
    }

    @Test
    void aBlankIdOrLabelIsRefused() {
        List<Fact> none = List.of();
        String longKind = "k".repeat(41);
        String longValue = "x".repeat(PanelAction.MAX_DESCRIPTION + 1);
        String longLabel = "l".repeat(201);

        assertThrows(IllegalArgumentException.class, () -> new Node(" ", "Note", "", none));
        assertThrows(IllegalArgumentException.class, () -> new Node("Note", "", "", none));
        assertThrows(IllegalArgumentException.class, () -> new Node("Note", "Note", longKind, none));
        assertThrows(IllegalArgumentException.class, () -> new Fact("", "NOTE"));
        assertThrows(IllegalArgumentException.class, () -> new Fact("table", longValue));
        assertThrows(IllegalArgumentException.class, () -> new Edge("Note", "Author", longLabel, true, false));
    }
}
