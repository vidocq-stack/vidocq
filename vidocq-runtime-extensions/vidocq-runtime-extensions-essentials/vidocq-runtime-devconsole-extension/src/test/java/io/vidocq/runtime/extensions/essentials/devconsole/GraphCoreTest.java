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
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A graph result in the page's pure half: the body read, and the layout of its boxes and lines, which must be the
 * same for the same graph, keep boxes apart, place a parent above its subclass, and end each line on its boxes.
 */
class GraphCoreTest {

    private static final String GRAPH = "{\"nodes\":["
            + "{\"id\":\"Base\",\"label\":\"Base\",\"kind\":\"mapped superclass\",\"facts\":[]},"
            + "{\"id\":\"Note\",\"label\":\"Note\",\"kind\":\"entity\",\"facts\":[[\"table\",\"NOTE\"]]},"
            + "{\"id\":\"Author\",\"label\":\"Author\",\"kind\":\"entity\",\"facts\":[]},"
            + "{\"id\":\"Tag\",\"label\":\"Tag\",\"kind\":\"entity\",\"facts\":[]},"
            + "{\"id\":\"Employee\",\"label\":\"Employee\",\"kind\":\"entity\",\"facts\":[]},"
            + "{\"id\":\"Address\",\"label\":\"Address\",\"kind\":\"embeddable\",\"facts\":[]}],"
            + "\"edges\":["
            + "{\"from\":\"Note\",\"to\":\"Base\",\"label\":\"extends\",\"arrow\":true,\"dashed\":false},"
            + "{\"from\":\"Note\",\"to\":\"Author\",\"label\":\"author *..1\",\"arrow\":false,\"dashed\":false},"
            + "{\"from\":\"Author\",\"to\":\"Note\",\"label\":\"notes 1..*\",\"arrow\":false,\"dashed\":false},"
            + "{\"from\":\"Note\",\"to\":\"Tag\",\"label\":\"tags *..*\",\"arrow\":false,\"dashed\":false},"
            + "{\"from\":\"Tag\",\"to\":\"Author\",\"label\":\"\",\"arrow\":false,\"dashed\":false},"
            + "{\"from\":\"Employee\",\"to\":\"Employee\",\"label\":\"manager\",\"arrow\":false,\"dashed\":false},"
            + "{\"from\":\"Employee\",\"to\":\"Address\",\"label\":\"home\",\"arrow\":false,\"dashed\":true}]}";

    /** Whether two boxes of a layout share a point, margins left aside. */
    private static final String OVERLAPS = "(layout) => { const n = layout.nodes; for (let i = 0; i < n.length; i++) "
            + "for (let j = i + 1; j < n.length; j++) { const a = n[i], b = n[j]; if (a.x < b.x + b.w && b.x < a.x + a.w "
            + "&& a.y < b.y + b.h && b.y < a.y + a.h) return a.id + ' over ' + b.id; } return ''; }";

    private static PageCore core;

    @BeforeAll
    static void loadTheModule() {
        core = new PageCore();
    }

    @AfterAll
    static void closeTheEngine() {
        core.close();
    }

    private static Value layout(String graph) {
        return core.run("graphLayout", core.run("readGraph", graph));
    }

    private static Value box(Value layout, String id) {
        return core.eval("(layout, id) => layout.nodes.find((n) => n.id === id)", layout, id);
    }

    @Test
    void aGraphBodyIsRead() {
        assertEquals(core.json(core.parse(GRAPH)), core.json(core.run("readGraph", GRAPH)));
    }

    @Test
    void anEdgeToAnUnknownNodeReadsAsNoGraph() {
        for (String odd : new String[] {"", "[]", "{\"nodes\":[]}", "{\"nodes\":{},\"edges\":[]}",
                "{\"nodes\":[{\"id\":\"A\",\"label\":\"A\",\"kind\":\"\",\"facts\":[]}],\"edges\":[{\"from\":\"A\","
                        + "\"to\":\"B\",\"label\":\"\",\"arrow\":false,\"dashed\":false}]}",
                "{\"nodes\":[{\"id\":\"A\",\"label\":\"A\",\"kind\":\"\",\"facts\":[]},{\"id\":\"A\",\"label\":\"A\","
                        + "\"kind\":\"\",\"facts\":[]}],\"edges\":[]}",
                "{\"nodes\":[{\"id\":\"A\",\"label\":\"A\",\"kind\":\"\",\"facts\":[[\"table\"]]}],\"edges\":[]}"}) {
            assertEquals("null", core.json(core.run("readGraph", odd)), odd);
        }
    }

    @Test
    void theLayoutIsTheSameForTheSameGraph() {
        String first = core.json(layout(GRAPH));

        assertEquals(first, core.json(layout(GRAPH)));
        assertTrue(!first.contains("null") && !first.contains("NaN"), first);
    }

    @Test
    void noTwoBoxesOverlap() {
        Value layout = layout(GRAPH);

        assertEquals("", core.eval(OVERLAPS, layout).asString());
        assertEquals(6, layout.getMember("nodes").getArraySize(), "every node has a box");
        assertTrue(core.eval("(l) => l.nodes.every((n) => n.x >= 0 && n.y >= 0 && n.x + n.w <= l.width "
                + "&& n.y + n.h <= l.height)", layout).asBoolean(), "inside the drawing");
    }

    @Test
    void aParentSitsAboveItsSubclass() {
        Value layout = layout(GRAPH);

        assertTrue(box(layout, "Base").getMember("y").asDouble() < box(layout, "Note").getMember("y").asDouble(),
                "Note, the most connected node, extends Base");
    }

    @Test
    void aSelfEdgeIsALoopOnItsBox() {
        Value loop = core.eval("(l) => l.edges.find((e) => e.from === 'Employee' && e.to === 'Employee')",
                layout(GRAPH));
        Value employee = box(layout(GRAPH), "Employee");

        assertTrue(loop.getMember("loop").asBoolean());
        assertEquals(employee.getMember("x").asDouble() + employee.getMember("w").asDouble(),
                loop.getMember("x1").asDouble(), 0.5, "it leaves from the right side of its box");
    }

    @Test
    void anEdgeEndsOnTheBordersOfItsBoxes() {
        String onBorder = "(l) => { const box = (id) => l.nodes.find((n) => n.id === id); "
                + "const on = (b, x, y) => { const e = 0.5; const inX = x >= b.x - e && x <= b.x + b.w + e; "
                + "const inY = y >= b.y - e && y <= b.y + b.h + e; return inX && inY && (Math.abs(x - b.x) < e "
                + "|| Math.abs(x - b.x - b.w) < e || Math.abs(y - b.y) < e || Math.abs(y - b.y - b.h) < e); }; "
                + "return l.edges.filter((e) => !e.loop && !(on(box(e.from), e.x1, e.y1) && on(box(e.to), e.x2, e.y2)))"
                + ".map((e) => e.from + '-' + e.to).join(' '); }";

        assertEquals("", core.eval(onBorder, layout(GRAPH)).asString());
    }

    @Test
    void twoEdgesBetweenTheSameBoxesAreDrawnApart() {
        Value layout = layout(GRAPH);
        Value there = core.eval("(l) => l.edges.find((e) => e.from === 'Note' && e.to === 'Author')", layout);
        Value back = core.eval("(l) => l.edges.find((e) => e.from === 'Author' && e.to === 'Note')", layout);

        assertTrue(Math.abs(there.getMember("lx").asDouble() - back.getMember("lx").asDouble())
                + Math.abs(there.getMember("ly").asDouble() - back.getMember("ly").asDouble()) > 6,
                "their labels do not sit on each other");
    }

    @Test
    void aLongLabelIsCutInItsBox() {
        String label = "AVeryLongEntityNameThatGoesOnAndOnAndOnAndOnForever";
        Value layout = layout("{\"nodes\":[{\"id\":\"A\",\"label\":\"" + label + "\",\"kind\":\"\",\"facts\":[]}],"
                + "\"edges\":[]}");
        Value a = box(layout, "A");

        assertEquals(label.substring(0, 39) + "…", a.getMember("text").asString());
        assertTrue(a.getMember("w").asDouble() < 320, a.toString());
    }

    @Test
    void fiveHundredNodesLayOutUnderOneSecond() {
        StringBuilder graph = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < 500; i++) {
            graph.append(i == 0 ? "" : ",").append("{\"id\":\"n").append(i).append("\",\"label\":\"Entity")
                    .append(i).append("\",\"kind\":\"entity\",\"facts\":[]}");
        }
        graph.append("],\"edges\":[");
        for (int i = 0; i < 1500; i++) {
            graph.append(i == 0 ? "" : ",").append("{\"from\":\"n").append(i % 500).append("\",\"to\":\"n")
                    .append((i * 7 + 3) % 500).append("\",\"label\":\"r\",\"arrow\":false,\"dashed\":false}");
        }
        String text = graph.append("]}").toString();
        layout(text);

        long start = System.nanoTime();
        Value layout = layout(text);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(500, layout.getMember("nodes").getArraySize());
        assertTrue(millis < 1000, millis + " ms");
    }
}
