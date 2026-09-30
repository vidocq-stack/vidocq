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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the continuous-testing entries (#122) of the hand-written {@code META-INF/maven/plugin.xml}: a typo there
 * leaves a field unset and the property silently ignored, since the unit tests call the mojos directly.
 */
class PluginDescriptorContinuousTestingTest {

    private static final Map<String, Element> MOJOS = new HashMap<>();

    @BeforeAll
    static void readDescriptor() throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file),
                "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (Element mojo : children(child(root, "mojos"))) {
            MOJOS.put(text(mojo, "goal"), mojo);
        }
    }

    @Test
    void devWatchesTheTestDirectories() throws Exception {
        assertConfigured("dev", "testWatchDirs", "java.lang.String", "${vidocq.dev.testWatchDirs}",
                "src/test/java,src/test/resources");
        assertEquals(String.class, VidocqDevMojo.class.getDeclaredField("testWatchDirs").getType());
    }

    /** No default, so that the application's files are read when neither -D nor the configuration sets it. */
    @Test
    void devHasAnUnsetContinuousTestingSwitch() throws Exception {
        assertConfigured("dev", "continuousTesting", "java.lang.Boolean", "${vidocq.dev.continuousTesting}", "");
        assertEquals(Boolean.class, VidocqDevMojo.class.getDeclaredField("continuousTesting").getType());
    }

    @Test
    void everyDevParameterIsAFieldOfTheMojo() throws Exception {
        assertParametersAreFields("dev", VidocqDevMojo.class);
    }

    @Test
    void devResolvesCompanionsThroughMavenResolver() throws Exception {
        assertParametersAreFields("dev", VidocqDevMojo.class);
        Element requirements = child(mojo("dev"), "requirements");
        assertNotNull(requirements, "the dev mojo needs the RepositorySystem");
        Element requirement = children(requirements).getFirst();
        assertEquals("org.eclipse.aether.RepositorySystem", text(requirement, "role"));
        assertEquals("repoSystem", text(requirement, "field-name"));
    }

    @Test
    void theTestGoalIsDeclaredForItsMojo() throws Exception {
        Element mojo = mojo("test");
        assertNotNull(mojo, "no <mojo> with <goal>test</goal>");
        assertEquals(VidocqTestMojo.class.getName(), text(mojo, "implementation"));
        assertEquals("true", text(mojo, "requiresDirectInvocation"));
        assertEquals("true", text(mojo, "requiresProject"));
        assertParametersAreFields("test", VidocqTestMojo.class);
    }

    @Test
    void theTestGoalReadsTheDevWatchProperties() {
        assertConfigured("test", "watchDirs", "java.lang.String", "${vidocq.dev.watchDirs}",
                "src/main/java,src/main/resources");
        assertConfigured("test", "testWatchDirs", "java.lang.String", "${vidocq.dev.testWatchDirs}",
                "src/test/java,src/test/resources");
        assertConfigured("test", "debounceMillis", "long", "${vidocq.dev.debounceMillis}", "250");
        assertConfigured("test", "devServices", "java.lang.Boolean", "${vidocq.dev.devServices}", "");
    }

    /** The PostgreSQL dev service looks for its driver on the test class path (spec 2026-09-29 §5). */
    @Test
    void theTestGoalResolvesTheTestClassPathAndGetsTheProject() throws Exception {
        Element mojo = mojo("test");
        assertEquals("test", text(mojo, "requiresDependencyResolution"));
        assertEquals("org.apache.maven.project.MavenProject", text(parameter(mojo, "project"), "type"));
        Element entry = child(child(mojo, "configuration"), "project");
        assertNotNull(entry, "<configuration> of test has no project entry");
        assertEquals("${project}", entry.getAttribute("default-value"));
        assertEquals("org.apache.maven.project.MavenProject", entry.getAttribute("implementation"));
        assertEquals("org.apache.maven.project.MavenProject",
                VidocqTestMojo.class.getDeclaredField("project").getType().getName());
    }

    /** #148: vidocq:dev also searches the plugin repositories for the dev console. */
    @Test
    void theDevGoalGetsThePluginRepositories() throws Exception {
        assertConfigured("dev", "pluginRepos", "java.util.List", "",
                "${project.remotePluginRepositories}");
        assertParametersAreFields("dev", VidocqDevMojo.class);
    }

    static void assertConfigured(String goal, String name, String type, String expression, String defaultValue) {
        Element mojo = MOJOS.get(goal);
        assertNotNull(mojo, "no <mojo> with <goal>" + goal + "</goal>");
        assertEquals(type, text(parameter(mojo, name), "type"));
        Element entry = child(child(mojo, "configuration"), name);
        assertNotNull(entry, "<configuration> of " + goal + " has no " + name + " entry");
        assertEquals(expression, entry.getTextContent().strip());
        assertEquals(defaultValue, entry.getAttribute("default-value"));
        assertEquals(type, entry.getAttribute("implementation"));
    }

    static void assertParametersAreFields(String goal, Class<?> mojo) throws Exception {
        for (Element parameter : children(child(MOJOS.get(goal), "parameters"))) {
            String name = text(parameter, "name");
            assertNotNull(mojo.getDeclaredField(name), goal + " declares " + name);
        }
    }

    static Element mojo(String goal) {
        return MOJOS.get(goal);
    }

    static Element parameter(Element mojo, String name) {
        for (Element element : children(child(mojo, "parameters"))) {
            if (name.equals(text(element, "name"))) {
                return element;
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent().strip();
    }

    static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }
}
