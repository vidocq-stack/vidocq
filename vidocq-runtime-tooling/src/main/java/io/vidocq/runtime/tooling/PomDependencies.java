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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code groupId:artifactId} of every dependency declared directly under
 * {@code <project>/<dependencies>} of a Maven POM, using StAX (zero extra dependency,
 * {@code java.xml} module). Dependencies nested elsewhere (e.g.
 * {@code <dependencyManagement>}, {@code <plugin>}) are intentionally ignored.
 *
 * <p>Pure: input is the POM text, output is the list of coordinates.</p>
 */
public final class PomDependencies {

    /** A {@code <plugin>} element, whose content is group 1; plugins do not nest. */
    private static final Pattern PLUGIN_BLOCK = Pattern.compile("<plugin>(.*?)</plugin>", Pattern.DOTALL);
    private static final String GROUP_ID = "groupId";
    private static final String ARTIFACT_ID = "artifactId";
    private static final String DEPENDENCY = "dependency";

    /** A reader {@code try} closes, {@link XMLStreamReader} not being {@link AutoCloseable}. */
    private interface ClosingReader extends AutoCloseable {
        @Override
        void close() throws XMLStreamException;
    }

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

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(pomXml));
            try (var _ = (ClosingReader) reader::close) {
                collectDependencies(reader, result);
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid pom.xml: " + e.getMessage(), e);
        }
        return result;
    }

    private static void collectDependencies(XMLStreamReader reader, List<ExtensionCoordinate> result)
            throws XMLStreamException {
        Deque<String> path = new ArrayDeque<>();
        String groupId = null;
        String artifactId = null;
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    path.addLast(reader.getLocalName());
                    text.setLength(0);
                    if (isProjectDependency(path)) {
                        groupId = null;
                        artifactId = null;
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    if (isProjectDependencyChild(path, GROUP_ID)) {
                        groupId = text.toString().trim();
                    } else if (isProjectDependencyChild(path, ARTIFACT_ID)) {
                        artifactId = text.toString().trim();
                    } else if (DEPENDENCY.equals(reader.getLocalName()) && isProjectDependency(path)
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
    }

    /**
     * The {@code groupId:artifactId} of every {@code <path>} of an {@code <annotationProcessorPaths>} in the project
     * build, its {@code pluginManagement} included, in order; a profile's are left out, as a build without the
     * profile does not run them.
     *
     * @throws IllegalArgumentException if the XML cannot be parsed
     */
    public static List<ExtensionCoordinate> processorPaths(String pomXml) {
        ProcessorPathCollector collector = new ProcessorPathCollector();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(pomXml));
            try (var _ = (ClosingReader) reader::close) {
                collectProcessorPaths(reader, collector);
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid pom.xml: " + e.getMessage(), e);
        }
        return collector.paths;
    }

    private static void collectProcessorPaths(XMLStreamReader reader, ProcessorPathCollector collector)
            throws XMLStreamException {
        List<String> path = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    path.add(reader.getLocalName());
                    text.setLength(0);
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    collector.end(path, text.toString().trim());
                    path.removeLast();
                    text.setLength(0);
                }
                default -> { /* ignore */ }
            }
        }
    }

    /** Collects the {@code <path>} elements of {@code <annotationProcessorPaths>} outside the profiles. */
    private static final class ProcessorPathCollector {
        final List<ExtensionCoordinate> paths = new ArrayList<>();
        private String groupId;
        private String artifactId;

        /** Called at the end of the last element of {@code path}, whose text is {@code text}. */
        void end(List<String> path, String text) {
            if (path.contains("profiles")) {
                return;
            }
            if (under(path, 2, "path")) {
                switch (path.getLast()) {
                    case GROUP_ID -> groupId = text;
                    case ARTIFACT_ID -> artifactId = text;
                    default -> { /* version, type, exclusions */ }
                }
            } else if (under(path, 1, "path")) {
                if (groupId != null && artifactId != null && !groupId.isBlank() && !artifactId.isBlank()) {
                    paths.add(new ExtensionCoordinate(groupId, artifactId));
                }
                groupId = null;
                artifactId = null;
            }
        }

        /** Whether the element {@code up} levels above the last one is a {@code path} of processor paths. */
        private static boolean under(List<String> path, int up, String element) {
            int at = path.size() - up;
            return at >= 1 && element.equals(path.get(at)) && "annotationProcessorPaths".equals(path.get(at - 1));
        }
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
                                case GROUP_ID    -> groupId = text.toString().trim();
                                case ARTIFACT_ID -> artifactId = text.toString().trim();
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

    /**
     * Whether the project gets the version of every Vidocq artifact from {@code vidocq-runtime-bom}: through a
     * {@code vidocq-runtime-parent} from 0.4.0, which imports it, or by importing it itself (Vidocq/vidocq#339). An
     * edit then writes no version, and an upgrade changes one line.
     */
    public static boolean versionsManaged(String pomXml) {
        if (runtimeVersion(pomXml).filter(ProjectScaffolder::parentPinsItsVersion).isPresent()) {
            return true;
        }
        int start = pomXml.indexOf("<dependencyManagement>");
        int end = pomXml.indexOf("</dependencyManagement>");
        return start >= 0 && end > start
                && pomXml.substring(start, end).contains("<artifactId>vidocq-runtime-bom</artifactId>");
    }

    /**
     * Whether {@code vidocq-runtime-maven-plugin}, loaded as a build extension, puts the codegen bundle of each
     * extension on the compiler's processor path at build time (Vidocq/vidocq#339, evolution 2): under a
     * {@code vidocq-runtime-parent} from 0.4.0, which declares it so, or when the pom declares it with
     * {@code <extensions>true</extensions>}. An extension is then its dependency alone.
     */
    public static boolean codegenWiredByPlugin(String pomXml) {
        if (runtimeVersion(pomXml).filter(ProjectScaffolder::parentPinsItsVersion).isPresent()) {
            return true;
        }
        Matcher plugin = PLUGIN_BLOCK.matcher(pomXml);
        while (plugin.find()) {
            String block = plugin.group(1);
            if (block.contains("<artifactId>vidocq-runtime-maven-plugin</artifactId>")
                    && block.replaceAll("\\s", "").contains("<extensions>true</extensions>")) {
                return true;
            }
        }
        return false;
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
                && DEPENDENCY.equals(it.next());
    }

    // path is project/dependencies/dependency/<child>
    private static boolean isProjectDependencyChild(Deque<String> path, String child) {
        if (path.size() != 4) {
            return false;
        }
        var it = path.iterator();
        return "project".equals(it.next())
                && "dependencies".equals(it.next())
                && DEPENDENCY.equals(it.next())
                && child.equals(it.next());
    }
}
