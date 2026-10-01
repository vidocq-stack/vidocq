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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the hand-written {@code META-INF/maven/plugin.xml} entries of {@code vidocq:generate} and
 * {@code vidocq:analyze-deps}: the descriptor is not generated from the annotations, so a parameter missing from it is
 * never injected — {@code autoScan} would stay {@code false} and the bean-archive detection silently off.
 */
class PluginDescriptorScanTest {

    private static final Set<String> SCAN_PARAMETERS = Set.of("scanDependencies", "scanExcludes", "autoScan");

    private static Map<String, Element> mojos;

    @BeforeAll
    static void readDescriptor() throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file),
                "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document descriptor = factory.newDocumentBuilder().parse(file.toFile());
        mojos = new LinkedHashMap<>();
        for (Element mojo : children(child(descriptor.getDocumentElement(), "mojos"))) {
            mojos.put(text(mojo, "goal"), mojo);
        }
    }

    @Test
    void generateDeclaresTheScanParametersWithTheDetectionOnByDefault() {
        Element generate = mojos.get("generate");
        assertNotNull(generate);
        assertTrue(parameterNames(generate).keySet().containsAll(SCAN_PARAMETERS), parameterNames(generate).keySet()
                .toString());
        Element autoScan = configuration(generate).get("autoScan");
        assertNotNull(autoScan, "autoScan has a configuration entry");
        assertEquals("true", autoScan.getAttribute("default-value"));
        assertEquals("${vidocq.generate.autoScan}", autoScan.getTextContent().strip());
    }

    @Test
    void analyzeDepsIsAGoalWithTheSameScanParameters() throws Exception {
        Element analyze = mojos.get("analyze-deps");
        assertNotNull(analyze, "no <mojo> with <goal>analyze-deps</goal>");
        assertEquals(VidocqAnalyzeDepsMojo.class, Class.forName(text(analyze, "implementation")));
        assertEquals("compile", text(analyze, "requiresDependencyResolution"));
        assertTrue(parameterNames(analyze).keySet().containsAll(SCAN_PARAMETERS));
        assertEquals(parameterNames(analyze).keySet(), configuration(analyze).keySet(),
                "every configuration entry names a declared parameter");
        assertEquals("true", configuration(analyze).get("autoScan").getAttribute("default-value"));
    }

    private static Map<String, Element> parameterNames(Element mojo) {
        Map<String, Element> byName = new LinkedHashMap<>();
        for (Element parameter : children(child(mojo, "parameters"))) {
            byName.put(text(parameter, "name"), parameter);
        }
        return byName;
    }

    private static Map<String, Element> configuration(Element mojo) {
        Map<String, Element> entries = new LinkedHashMap<>();
        for (Element entry : children(child(mojo, "configuration"))) {
            entries.put(entry.getTagName(), entry);
        }
        return entries;
    }

    private static Element child(Element parent, String name) {
        for (Element e : children(parent)) {
            if (e.getTagName().equals(name)) {
                return e;
            }
        }
        throw new AssertionError("no <" + name + "> under <" + parent.getTagName() + ">");
    }

    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent().strip();
    }

    private static java.util.List<Element> children(Element parent) {
        java.util.List<Element> list = new java.util.ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                list.add(e);
            }
        }
        return list;
    }
}
