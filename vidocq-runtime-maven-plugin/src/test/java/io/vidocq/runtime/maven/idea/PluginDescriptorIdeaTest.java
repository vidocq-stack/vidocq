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
package io.vidocq.runtime.maven.idea;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Field;
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
 * Guards the hand-written {@code META-INF/maven/plugin.xml} entry of {@code vidocq:idea}.
 *
 * <p>The descriptor is not generated from the annotations ({@code maven-plugin-plugin}'s
 * {@code default-descriptor} execution is bound to phase {@code none}), and the unit tests call the mojo
 * directly, so nothing else would notice a typo in a parameter name, a missing property expression or a
 * wrong type. The file is read from {@code target/classes} by path: the {@code vauban-maven-plugin}
 * dependency ships its own {@code META-INF/maven/plugin.xml} on the test class path.
 */
class PluginDescriptorIdeaTest {

    private static Document descriptor;
    private static Element idea;

    @BeforeAll
    static void readDescriptor() throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file), "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        descriptor = factory.newDocumentBuilder().parse(file.toFile());
        NodeList mojos = descriptor.getElementsByTagName("mojo");
        for (int i = 0; i < mojos.getLength(); i++) {
            Element mojo = (Element) mojos.item(i);
            if ("idea".equals(text(mojo, "goal"))) {
                idea = mojo;
            }
        }
        assertNotNull(idea, "no <mojo> with <goal>idea</goal> in " + file);
    }

    @Test
    void thePluginKeepsItsPrefix() {
        assertEquals("vidocq", text(descriptor.getDocumentElement(), "goalPrefix"));
    }

    @Test
    void theGoalIsACommandLineAggregatorWithoutPhase() throws Exception {
        Class<?> implementation = Class.forName(text(idea, "implementation"));

        assertEquals(VidocqIdeaMojo.class, implementation);
        assertTrue(AbstractMojo.class.isAssignableFrom(implementation));
        assertEquals("true", text(idea, "aggregator"));
        assertEquals("true", text(idea, "threadSafe"));
        assertEquals("true", text(idea, "requiresDirectInvocation"));
        assertEquals("true", text(idea, "requiresProject"));
        assertEquals("none", text(idea, "requiresDependencyResolution"));
        assertEquals("per-lookup", text(idea, "instantiationStrategy"));
        assertNull(child(idea, "phase"), "vidocq:idea writes into the source tree and must never run in a build");
    }

    @Test
    void everyParameterIsAFieldOfAnAssignableType() throws Exception {
        Map<String, String> parameters = parameters();

        assertEquals(List.of("session", "reactorProjects", "mojoExecution", "projectDirectory", "check",
                "generateBeforeLaunch", "jre", "skip"), new ArrayList<>(parameters.keySet()));
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            Field field = VidocqIdeaMojo.class.getDeclaredField(parameter.getKey());
            Class<?> declared = switch (parameter.getValue()) {
                case "boolean" -> boolean.class;
                default -> Class.forName(parameter.getValue());
            };
            assertTrue(declared == field.getType() || field.getType().isAssignableFrom(declared),
                    parameter.getKey() + ": " + parameter.getValue() + " vs " + field.getType());
        }
        assertEquals(MavenSession.class, VidocqIdeaMojo.class.getDeclaredField("session").getType());
        assertEquals(MojoExecution.class, VidocqIdeaMojo.class.getDeclaredField("mojoExecution").getType());
        assertEquals(File.class, VidocqIdeaMojo.class.getDeclaredField("projectDirectory").getType());
    }

    @Test
    void theConfigurationReadsTheDocumentedPropertiesAndDefaults() {
        Element configuration = child(idea, "configuration");
        Map<String, Element> entries = new LinkedHashMap<>();
        for (Element entry : children(configuration)) {
            entries.put(entry.getTagName(), entry);
        }

        assertEquals(parameters().keySet(), entries.keySet(), "every configuration entry names a declared parameter");
        assertEquals("${session}", entries.get("session").getAttribute("default-value"));
        assertEquals("${reactorProjects}", entries.get("reactorProjects").getAttribute("default-value"));
        assertEquals("${mojoExecution}", entries.get("mojoExecution").getAttribute("default-value"));
        assertProperty(entries.get("projectDirectory"), "${vidocq.idea.projectDirectory}", "");
        assertProperty(entries.get("check"), "${vidocq.idea.check}", "false");
        assertProperty(entries.get("generateBeforeLaunch"), "${vidocq.idea.generateBeforeLaunch}", "true");
        assertProperty(entries.get("jre"), "${vidocq.idea.jre}", "");
        assertProperty(entries.get("skip"), "${vidocq.idea.skip}", "false");
    }

    private static void assertProperty(Element entry, String expression, String defaultValue) {
        assertEquals(expression, entry.getTextContent().strip(), entry.getTagName());
        assertEquals(defaultValue, entry.getAttribute("default-value"), entry.getTagName());
    }

    private static Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Element parameter : children(child(idea, "parameters"))) {
            parameters.put(text(parameter, "name"), text(parameter, "type"));
        }
        return parameters;
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
