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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

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
 * Guards the hand-written {@code META-INF/maven/plugin.xml} entry of {@code vidocq:run}.
 *
 * <p>The descriptor is not generated from the annotations ({@code maven-plugin-plugin}'s
 * {@code default-descriptor} execution is bound to phase {@code none}), and the unit tests call the mojo
 * directly, so nothing else would notice a typo in a parameter name, a missing property expression or a
 * missing {@code executePhase} — and without that last one, {@code mvn vidocq:run} would run an application
 * that was never compiled nor indexed, which is the whole point of the goal.
 */
class PluginDescriptorRunTest {

    private static Document descriptor;
    private static Element run;

    @BeforeAll
    static void readDescriptor() throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file),
                "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        descriptor = factory.newDocumentBuilder().parse(file.toFile());
        for (Element mojo : elements(descriptor.getDocumentElement(), "mojos")) {
            if ("run".equals(text(mojo, "goal"))) {
                run = mojo;
            }
        }
        assertNotNull(run, "no <mojo> with <goal>run</goal> in " + file);
    }

    @Test
    void theGoalForksTheLifecycleUpToTheIndexingPhase() throws Exception {
        assertEquals(VidocqRunMojo.class, Class.forName(text(run, "implementation")));
        assertTrue(AbstractMojo.class.isAssignableFrom(VidocqRunMojo.class));
        // vidocq:generate binds to process-classes: `mvn vidocq:run` alone must compile and index.
        assertEquals("process-classes", text(run, "executePhase"));
        assertNull(child(run, "phase"), "vidocq:run blocks until the application exits: never bind it to a build");
        assertEquals("runtime", text(run, "requiresDependencyResolution"));
        assertEquals("true", text(run, "requiresDirectInvocation"));
        assertEquals("true", text(run, "requiresProject"));
        assertEquals("false", text(run, "aggregator"));
        assertEquals("true", text(run, "threadSafe"));
        assertEquals("per-lookup", text(run, "instantiationStrategy"));
    }

    @Test
    void everyParameterIsAFieldOfAnAssignableType() throws Exception {
        Map<String, String> parameters = parameters();

        assertEquals(List.of("project", "session", "mainModule", "mainClass", "extraJvmArgs", "appArgs",
                        "extraSystemProperties", "debug", "debugPort", "debugHost", "debugSuspend", "gracePeriodMillis", "skip",
                        "devServices", "classesDir", "baseDir", "buildDir", "pluginArtifactMap"),
                new ArrayList<>(parameters.keySet()));
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            Field field = VidocqRunMojo.class.getDeclaredField(parameter.getKey());
            Class<?> declared = switch (parameter.getValue()) {
                case "boolean" -> boolean.class;
                case "int" -> int.class;
                case "long" -> long.class;
                default -> Class.forName(parameter.getValue());
            };
            assertTrue(declared == field.getType() || field.getType().isAssignableFrom(declared),
                    parameter.getKey() + ": " + parameter.getValue() + " vs " + field.getType());
        }
        assertEquals(MavenSession.class, VidocqRunMojo.class.getDeclaredField("session").getType());
        assertEquals(File.class, VidocqRunMojo.class.getDeclaredField("baseDir").getType());
    }

    /**
     * Every parameter reads a property, so that it can be set in the module's pom and not only on the command
     * line: {@code vidocq.mainModule} and {@code vidocq.mainClass} are the ones {@code vidocq:generate} and
     * {@code vidocq:package} already read there.
     */
    @Test
    void theConfigurationReadsTheDocumentedPropertiesAndDefaults() {
        Map<String, Element> entries = new LinkedHashMap<>();
        for (Element entry : children(child(run, "configuration"))) {
            entries.put(entry.getTagName(), entry);
        }

        assertEquals(parameters().keySet(), entries.keySet(), "every configuration entry names a declared parameter");
        assertEquals("${project}", entries.get("project").getAttribute("default-value"));
        assertEquals("${session}", entries.get("session").getAttribute("default-value"));
        assertProperty(entries.get("mainModule"), "${vidocq.mainModule}", "");
        assertProperty(entries.get("mainClass"), "${vidocq.mainClass}", "");
        assertProperty(entries.get("extraJvmArgs"), "${vidocq.run.jvmArgs}", "");
        assertProperty(entries.get("appArgs"), "${vidocq.run.args}", "");
        assertProperty(entries.get("extraSystemProperties"), "${vidocq.run.systemProperties}", "");
        assertProperty(entries.get("debug"), "${vidocq.run.debug}", "false");
        assertProperty(entries.get("debugPort"), "${vidocq.run.debug.port}", "5005");
        assertProperty(entries.get("debugHost"), "${vidocq.run.debug.host}", "127.0.0.1");
        assertProperty(entries.get("debugSuspend"), "${vidocq.run.debug.suspend}", "false");
        assertProperty(entries.get("gracePeriodMillis"), "${vidocq.run.gracePeriodMillis}", "5000");
        assertProperty(entries.get("skip"), "${vidocq.run.skip}", "false");
        // Same property as vidocq:dev's own devServices, and no default: unset, the application's files decide,
        // else off (VidocqRunMojo#devServicesEnabled), since vidocq:run is also how the application runs in CI.
        assertProperty(entries.get("devServices"), "${vidocq.dev.devServices}", "");
        assertEquals("java.lang.Boolean", entries.get("devServices").getAttribute("implementation"));
        assertEquals("${project.build.outputDirectory}", entries.get("classesDir").getAttribute("default-value"));
        assertEquals("${project.basedir}", entries.get("baseDir").getAttribute("default-value"));
        assertEquals("${project.build.directory}", entries.get("buildDir").getAttribute("default-value"));
        assertEquals("${plugin.artifactMap}", entries.get("pluginArtifactMap").getAttribute("default-value"),
                "resolves this plugin's own dependencies, so DevServicesExtensionJar can find its jar");
    }

    @Test
    void theMainModuleIsRequired() {
        assertEquals("true", required("mainModule"));
        assertEquals("false", required("mainClass"));
    }

    private static String required(String parameter) {
        for (Element element : children(child(run, "parameters"))) {
            if (parameter.equals(text(element, "name"))) {
                return text(element, "required");
            }
        }
        throw new AssertionError("no parameter " + parameter);
    }

    private static void assertProperty(Element entry, String expression, String defaultValue) {
        assertEquals(expression, entry.getTextContent().strip(), entry.getTagName());
        assertEquals(defaultValue, entry.getAttribute("default-value"), entry.getTagName());
    }

    private static Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Element parameter : children(child(run, "parameters"))) {
            parameters.put(text(parameter, "name"), text(parameter, "type"));
        }
        return parameters;
    }

    private static List<Element> elements(Element root, String container) {
        return children(child(root, container));
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
