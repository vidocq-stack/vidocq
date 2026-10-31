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
package io.vidocq.runtime.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the hand-written {@code META-INF/maven/plugin.xml} entry of {@code vidocq:create}
 * (Vidocq/vidocq#314): the descriptor is not generated from the annotations, so
 * without it the goals do not exist, whatever the classes say.
 */
class PluginDescriptorCreateTest {

    @Test
    void theGoalNeedsNoProject() throws Exception {
        Element mojo = mojo("create");

        assertEquals(VidocqCreateMojo.class, Class.forName(text(mojo, "implementation")));
        assertTrue(AbstractMojo.class.isAssignableFrom(VidocqCreateMojo.class));
        assertNull(child(mojo, "phase"));
        assertEquals("false", text(mojo, "requiresProject"), "it creates the project");
        assertEquals("none", text(mojo, "requiresDependencyResolution"));
        assertEquals("true", text(mojo, "threadSafe"));
    }

    @Test
    void theParametersAreTheFieldsAndReadTheDocumentedProperties() throws Exception {
        Element mojo = mojo("create");
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Element parameter : children(child(mojo, "parameters"))) {
            parameters.put(text(parameter, "name"), text(parameter, "type"));
        }
        Map<String, Element> configuration = new LinkedHashMap<>();
        for (Element entry : children(child(mojo, "configuration"))) {
            configuration.put(entry.getTagName(), entry);
        }

        assertEquals(List.of("basedir", "name", "groupId", "pkg", "extensions", "parentVersion", "main",
                "openModule", "runtimeVersion"), new ArrayList<>(parameters.keySet()));
        assertEquals(parameters.keySet(), configuration.keySet());
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            Class<?> type = parameter.getValue().equals("boolean") ? boolean.class : Class.forName(parameter.getValue());
            assertEquals(type, VidocqCreateMojo.class.getDeclaredField(parameter.getKey()).getType(), parameter.getKey());
        }
        assertEquals("${basedir}", configuration.get("basedir").getAttribute("default-value"));
        for (String property : List.of("name", "groupId", "extensions", "parentVersion", "main", "openModule")) {
            assertEquals("${" + property + "}", configuration.get(property).getTextContent().strip(), property);
        }
        assertEquals("${package}", configuration.get("pkg").getTextContent().strip());
        assertEquals("${plugin.version}", configuration.get("runtimeVersion").getAttribute("default-value"));
    }

    private static Element mojo(String goal) throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file),
                "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document descriptor = factory.newDocumentBuilder().parse(file.toFile());
        for (Element mojo : children(child(descriptor.getDocumentElement(), "mojos"))) {
            if (goal.equals(text(mojo, "goal"))) {
                return mojo;
            }
        }
        throw new AssertionError("no <mojo> with <goal>" + goal + "</goal> in " + file);
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent().strip();
    }

    private static Element child(Element parent, String name) {
        assertNotNull(parent);
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
