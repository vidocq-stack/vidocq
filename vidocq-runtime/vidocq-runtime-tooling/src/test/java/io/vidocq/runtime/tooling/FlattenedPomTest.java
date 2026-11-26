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
package io.vidocq.runtime.tooling;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module with code is installed and published with a flattened pom ({@code flatten-published} profile of
 * {@code vidocq-parent}): a patch release publishes only the modules that changed, and a module of it must not take
 * the versions of its dependencies from a parent published with an earlier release. This module is one of them: the
 * build wrote its pom before the tests ran.
 */
class FlattenedPomTest {

    private static final Path FLATTENED = Path.of(".flattened-pom.xml");

    @Test
    void theModuleIsPublishedWithoutParentAndWithTheVersionOfEachDependency() throws Exception {
        assertTrue(Files.isRegularFile(FLATTENED), "no " + FLATTENED.toAbsolutePath() + ": the pom is not flattened");
        Element project = parse(FLATTENED);

        assertTrue(children(project, "parent").isEmpty(), "the flattened pom keeps a parent");
        List<String> unversioned = new ArrayList<>();
        for (Element dependencies : children(project, "dependencies")) {
            for (Element d : children(dependencies, "dependency")) {
                String version = text(d, "version");
                if (version == null || version.contains("${")) {
                    unversioned.add(text(d, "groupId") + ":" + text(d, "artifactId") + " " + version);
                }
            }
        }
        assertEquals(List.of(), unversioned, "dependencies without a resolved version");
    }

    @Test
    void theModuleKeepsWhatMavenCentralRequires() throws Exception {
        Element project = parse(FLATTENED);

        for (String element : List.of("name", "description", "url", "licenses", "developers", "scm")) {
            assertEquals(1, children(project, element).size(), "the flattened pom has no <" + element + ">");
        }
    }

    private static Element parse(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE && name.equals(nodes.item(i).getNodeName())) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst().getTextContent().trim();
    }
}
