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
package io.vidocq.runtime.cli.ext;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code groupId:artifactId} of every dependency declared directly under
 * {@code <project>/<dependencies>} of a Maven POM, using StAX (zero extra dependency,
 * {@code java.xml} module). Dependencies nested elsewhere (e.g.
 * {@code <dependencyManagement>}, {@code <plugin>}) are intentionally ignored.
 *
 * <p>Pure: input is the POM text, output is the list of coordinates.</p>
 */
public final class PomDependencies {

    private PomDependencies() {}

    /**
     * @throws IllegalArgumentException if the XML cannot be parsed
     */
    public static List<ExtensionCoordinate> parse(String pomXml) {
        List<ExtensionCoordinate> result = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // Harden against XXE — we only need element/character events.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(pomXml));
            Deque<String> path = new ArrayDeque<>();
            String groupId = null;
            String artifactId = null;
            StringBuilder text = new StringBuilder();

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        path.addLast(reader.getLocalName());
                        text.setLength(0);
                        if (isProjectDependency(path)) {
                            groupId = null;
                            artifactId = null;
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                            text.append(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        String local = reader.getLocalName();
                        if (isProjectDependencyChild(path, "groupId")) {
                            groupId = text.toString().trim();
                        } else if (isProjectDependencyChild(path, "artifactId")) {
                            artifactId = text.toString().trim();
                        } else if ("dependency".equals(local) && isProjectDependency(path)
                                && groupId != null && !groupId.isBlank()
                                && artifactId != null && !artifactId.isBlank()) {
                            result.add(new ExtensionCoordinate(groupId, artifactId));
                        }
                        path.removeLast();
                        text.setLength(0);
                    }
                    default -> { /* ignore */ }
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid pom.xml: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // best effort
                }
            }
        }
        return result;
    }

    /** The {@code <project>/<parent>} coordinates, when the POM declares a parent. */
    public record Parent(String groupId, String artifactId, String version) {}

    /**
     * Reads the {@code <project>/<parent>} coordinates.
     *
     * @throws IllegalArgumentException if the XML cannot be parsed
     */
    public static Optional<Parent> parent(String pomXml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XMLStreamReader reader = null;
        String groupId = null;
        String artifactId = null;
        String version = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(pomXml));
            Deque<String> path = new ArrayDeque<>();
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        path.addLast(reader.getLocalName());
                        text.setLength(0);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                            text.append(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (path.size() == 3 && "parent".equals(path.toArray()[1])) {
                            switch (reader.getLocalName()) {
                                case "groupId"    -> groupId = text.toString().trim();
                                case "artifactId" -> artifactId = text.toString().trim();
                                case "version"    -> version = text.toString().trim();
                                default -> { /* relativePath */ }
                            }
                        }
                        path.removeLast();
                        text.setLength(0);
                    }
                    default -> { /* ignore */ }
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid pom.xml: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // best effort
                }
            }
        }
        return groupId == null || artifactId == null || version == null
                ? Optional.empty()
                : Optional.of(new Parent(groupId, artifactId, version));
    }

    /**
     * The Vidocq runtime version a project builds against: its
     * {@code io.vidocq.runtime:vidocq-runtime-parent} version. Empty for another parent.
     */
    public static Optional<String> runtimeVersion(String pomXml) {
        return parent(pomXml)
                .filter(p -> p.groupId().equals("io.vidocq.runtime")
                        && p.artifactId().equals("vidocq-runtime-parent"))
                .map(Parent::version);
    }

    /** True when {@code id} appears in the project's dependency list. */
    public static boolean contains(String pomXml, ExtensionCoordinate coordinate) {
        return parse(pomXml).contains(coordinate);
    }

    // path is project/dependencies/dependency (the current top element being a dependency)
    private static boolean isProjectDependency(Deque<String> path) {
        if (path.size() != 3) {
            return false;
        }
        var it = path.iterator();
        return "project".equals(it.next())
                && "dependencies".equals(it.next())
                && "dependency".equals(it.next());
    }

    // path is project/dependencies/dependency/<child>
    private static boolean isProjectDependencyChild(Deque<String> path, String child) {
        if (path.size() != 4) {
            return false;
        }
        var it = path.iterator();
        return "project".equals(it.next())
                && "dependencies".equals(it.next())
                && "dependency".equals(it.next())
                && child.equals(it.next());
    }
}
