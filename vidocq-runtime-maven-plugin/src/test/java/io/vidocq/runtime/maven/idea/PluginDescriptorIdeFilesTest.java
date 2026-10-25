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

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The hand-written descriptors of the goals that write IDE files match their mojos. */
class PluginDescriptorIdeFilesTest {

    @Test
    void vscode() throws Exception {
        Element configuration = descriptorMatches("vscode", VidocqVscodeMojo.class);

        assertEquals("${vidocq.vscode.check}", configuration.getElementsByTagName("check").item(0).getTextContent());
        assertEquals("${vidocq.vscode.jdk}", configuration.getElementsByTagName("jdk").item(0).getTextContent());
    }

    /** Every field of the mojo, its base's first, is a parameter of the goal; returns the goal's configuration. */
    private static Element descriptorMatches(String goal, Class<?> type) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        NodeList mojos = factory.newDocumentBuilder()
                .parse(Path.of("target", "classes", "META-INF", "maven", "plugin.xml").toFile())
                .getElementsByTagName("mojo");
        Element found = null;
        for (int i = 0; i < mojos.getLength(); i++) {
            Element mojo = (Element) mojos.item(i);
            if (goal.equals(mojo.getElementsByTagName("goal").item(0).getTextContent())) {
                found = mojo;
            }
        }
        assertNotNull(found, "no " + goal + " mojo in plugin.xml");
        assertEquals(type.getName(), found.getElementsByTagName("implementation").item(0).getTextContent());

        List<String> fields = new ArrayList<>();
        for (Class<?> c : List.of(IdeFilesMojo.class, type)) {
            Arrays.stream(c.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .map(Field::getName).forEach(fields::add);
        }
        List<String> parameters = new ArrayList<>();
        NodeList names = ((Element) found.getElementsByTagName("parameters").item(0)).getElementsByTagName("name");
        for (int i = 0; i < names.getLength(); i++) {
            parameters.add(names.item(i).getTextContent());
        }
        assertEquals(fields, parameters);
        return (Element) found.getElementsByTagName("configuration").item(0);
    }
}
