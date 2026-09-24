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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the debugger parameters of {@code vidocq:dev} in the hand-written {@code META-INF/maven/plugin.xml}.
 *
 * <p>The descriptor is not generated from the annotations and the unit tests call the mojo directly: a typo in
 * the {@code debugHost} entry would leave the field {@code null} — the default host, so nothing would fail — and
 * {@code -Dvidocq.dev.debugHost} would silently not open the agent, or a wrong default would open it to the
 * network.
 */
class PluginDescriptorDevDebugTest {

    private static Element dev;

    @BeforeAll
    static void readDescriptor() throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file),
                "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (Element mojo : children(child(root, "mojos"))) {
            if ("dev".equals(text(mojo, "goal"))) {
                dev = mojo;
            }
        }
        assertNotNull(dev, "no <mojo> with <goal>dev</goal> in " + file);
    }

    @Test
    void theDebugHostIsAStringParameterOfTheMojo() throws Exception {
        Element parameter = parameter("debugHost");

        assertEquals("java.lang.String", text(parameter, "type"));
        assertEquals("false", text(parameter, "required"));
        assertEquals(String.class, VidocqDevMojo.class.getDeclaredField("debugHost").getType());
    }

    @Test
    void theDebugHostReadsItsPropertyAndDefaultsToLoopback() {
        Element entry = child(child(dev, "configuration"), "debugHost");

        assertNotNull(entry, "<configuration> has no debugHost entry");
        assertEquals("${vidocq.dev.debugHost}", entry.getTextContent().strip());
        assertEquals("127.0.0.1", entry.getAttribute("default-value"));
        assertEquals("java.lang.String", entry.getAttribute("implementation"));
    }

    /**
     * {@code devServices} has no default, so that {@code vidocq.dev.devServices} in the application's files is read
     * when neither {@code -D} nor the goal's configuration sets it ({@link VidocqDevMojo#devServicesEnabled}).
     */
    @Test
    void devServicesIsAnUnsetBooleanReadingItsProperty() throws Exception {
        assertEquals("java.lang.Boolean", text(parameter("devServices"), "type"));
        assertEquals(Boolean.class, VidocqDevMojo.class.getDeclaredField("devServices").getType());
        Element entry = child(child(dev, "configuration"), "devServices");
        assertNotNull(entry, "<configuration> has no devServices entry");
        assertEquals("${vidocq.dev.devServices}", entry.getTextContent().strip());
        assertEquals("", entry.getAttribute("default-value"), "no default: the application's files must be read");
    }

    private static Element parameter(String name) {
        for (Element element : children(child(dev, "parameters"))) {
            if (name.equals(text(element, "name"))) {
                return element;
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent().strip();
    }

    private static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }
}
