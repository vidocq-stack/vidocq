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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns the pom of a Vidocq application from one style into the other (Vidocq/vidocq#380): with
 * {@code vidocq-runtime-parent}, or without it, on the BOM and the plugin as a build extension, which gives the build
 * what the parent carried. {@code vidocq pom convert --to parentless|parent} and {@code vidocq:convert} run it.
 *
 * <p>Works on the text, as {@link PomEditor} does, so that everything that is not the style keeps its place:
 * dependencies, properties, plugins, comments, indentation. The lines it writes and removes are the ones
 * {@link ProjectScaffolder} writes in each style, so converting what the scaffolder wrote gives what the scaffolder
 * writes in the other style, and converting a pom to the style it has changes nothing.</p>
 *
 * <p>Pure: takes the pom text and returns a {@link Result}; no I/O. A pom it does not recognise (no Vidocq plugin, a
 * parent of its own when asked for the Vidocq one, no Vidocq version to be found) is refused with an
 * {@link IllegalArgumentException} that says why, and nothing is written.</p>
 */
public final class PomConverter {

    /** The style to convert to. */
    public enum Target {
        /** No parent: the BOM import, the plugin as a build extension, the Java release in the pom. */
        PARENTLESS,
        /** {@code vidocq-runtime-parent}, which carries all of that. */
        PARENT;

        /** The target {@code name} means, as the command line spells it; empty when it means none. */
        public static Optional<Target> of(String name) {
            for (Target target : values()) {
                if (target.name().equalsIgnoreCase(name)) {
                    return Optional.of(target);
                }
            }
            return Optional.empty();
        }
    }

    /** Outcome of a conversion: the (possibly unchanged) pom text and whether it changed. */
    public record Result(String pom, boolean changed) {}

    static final String RUNTIME_GROUP = "io.vidocq.runtime";
    static final String PARENT_ARTIFACT = "vidocq-runtime-parent";
    static final String PLUGIN_ARTIFACT = "vidocq-runtime-maven-plugin";
    static final String BOM_ARTIFACT = "vidocq-runtime-bom";
    static final String VERSION_PROPERTY = "vidocq.version";
    private static final String VERSION_REFERENCE = "${" + VERSION_PROPERTY + "}";
    private static final String RELEASE_PROPERTY = "maven.compiler.release";
    private static final String M2E_APT_PROPERTY = "m2e.apt.activation";
    private static final String DEFAULT_INDENT = "    ";
    private static final String NOT_RECOGNISED = "the pom is not recognised as a Vidocq application's: ";

    private PomConverter() {}

    /** The pom in the {@code target} style. */
    public static Result convert(String pom, Target target) {
        String edited = target == Target.PARENTLESS ? toParentless(pom) : toParent(pom);
        return new Result(edited, !edited.equals(pom));
    }

    // ---- to the parentless style ----

    private static String toParentless(String pom) {
        String version = vidocqVersion(pom);
        String unit = indentUnit(pom);
        String edited = pom;
        Optional<Element> parent = topLevel(edited, "parent");
        if (parent.isPresent() && isVidocqParent(edited, parent.get())) {
            edited = removeLines(edited, parent.get());
        }
        edited = withProjectVersion(edited, unit);
        edited = withProperties(edited, unit, version);
        edited = withManagedBoms(edited, unit);
        edited = withPluginAsExtension(edited, unit);
        return edited;
    }

    /** The project's own version, {@value ProjectScaffolder#APPLICATION_VERSION} when the parent gave it one. */
    private static String withProjectVersion(String pom, String unit) {
        if (topLevel(pom, "version").isPresent()) {
            return pom;
        }
        Element artifactId = topLevel(pom, "artifactId").orElseThrow(() -> refused("no <artifactId>"));
        return insert(pom, lineEnd(pom, artifactId.end()),
                unit + "<version>" + ProjectScaffolder.APPLICATION_VERSION + "</version>\n");
    }

    /** {@code vidocq.version} and the Java release first among the properties, where the pom lacks them. */
    private static String withProperties(String pom, String unit, String version) {
        StringBuilder lines = new StringBuilder();
        Optional<Element> properties = topLevel(pom, "properties");
        if (properties.isEmpty() || child(pom, properties.get(), VERSION_PROPERTY).isEmpty()) {
            lines.append(unit).append(unit).append(element(VERSION_PROPERTY, version)).append('\n');
        }
        if (properties.isEmpty() || !setsTheRelease(pom, properties.get())) {
            lines.append(unit).append(unit).append(element(RELEASE_PROPERTY, ProjectScaffolder.JAVA_RELEASE))
                    .append('\n');
        }
        if (lines.isEmpty()) {
            return pom;
        }
        if (properties.isPresent()) {
            Optional<Element> existing = child(pom, properties.get(), VERSION_PROPERTY);
            // The release goes after an existing vidocq.version, else both go first.
            int at = existing.map(e -> lineEnd(pom, e.end())).orElse(lineEnd(pom, properties.get().start()));
            return insert(pom, at, lines.toString());
        }
        Element before = topLevel(pom, "name").or(() -> topLevel(pom, "version"))
                .or(() -> topLevel(pom, "artifactId")).orElseThrow(() -> refused("no <artifactId>"));
        return insert(pom, lineEnd(pom, before.end()),
                "\n" + unit + "<properties>\n" + lines + unit + "</properties>\n");
    }

    private static boolean setsTheRelease(String pom, Element properties) {
        for (String name : new String[] {RELEASE_PROPERTY, "maven.compiler.source", "maven.compiler.target"}) {
            if (child(pom, properties, name).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** The BOM and the JUnit BOM imported, in a {@code <dependencyManagement>} created before the dependencies. */
    private static String withManagedBoms(String pom, String unit) {
        Optional<Element> management = topLevel(pom, "dependencyManagement");
        String bom = unit.repeat(3) + "<dependency>\n"
                + unit.repeat(4) + element("groupId", RUNTIME_GROUP) + "\n"
                + unit.repeat(4) + element("artifactId", BOM_ARTIFACT) + "\n"
                + unit.repeat(4) + element("version", VERSION_REFERENCE) + "\n"
                + unit.repeat(4) + "<type>pom</type>\n"
                + unit.repeat(4) + "<scope>import</scope>\n"
                + unit.repeat(3) + "</dependency>\n";
        String junit = unit.repeat(3) + "<dependency>\n"
                + unit.repeat(4) + element("groupId", "org.junit") + "\n"
                + unit.repeat(4) + element("artifactId", "junit-bom") + "\n"
                + unit.repeat(4) + element("version", ProjectScaffolder.JUNIT_BOM_VERSION) + "\n"
                + unit.repeat(4) + "<type>pom</type>\n"
                + unit.repeat(4) + "<scope>import</scope>\n"
                + unit.repeat(3) + "</dependency>\n";
        if (management.isEmpty()) {
            Element before = topLevel(pom, "dependencies").or(() -> topLevel(pom, "build"))
                    .orElseThrow(() -> refused("no <dependencies> and no <build>"));
            return insert(pom, lineStart(pom, before.start()), unit + "<dependencyManagement>\n"
                    + unit + unit + "<dependencies>\n" + bom + junit + unit + unit + "</dependencies>\n"
                    + unit + "</dependencyManagement>\n\n");
        }
        Element dependencies = child(pom, management.get(), "dependencies").orElse(null);
        if (dependencies == null) {
            return insert(pom, lineEnd(pom, management.get().start()),
                    unit + unit + "<dependencies>\n" + bom + junit + unit + unit + "</dependencies>\n");
        }
        StringBuilder missing = new StringBuilder();
        if (dependency(pom, dependencies, RUNTIME_GROUP, BOM_ARTIFACT).isEmpty()) {
            missing.append(bom);
        }
        if (dependency(pom, dependencies, "org.junit", "junit-bom").isEmpty()) {
            missing.append(junit);
        }
        return missing.isEmpty() ? pom : insert(pom, lineEnd(pom, dependencies.start()), missing.toString());
    }

    /** The plugin at {@code ${vidocq.version}}, as a build extension, with no execution the plugin binds itself. */
    private static String withPluginAsExtension(String pom, String unit) {
        Element plugin = plugin(pom);
        String edited = pom;
        Optional<Element> version = child(edited, plugin, "version");
        if (version.isPresent() && !VERSION_REFERENCE.equals(text(edited, version.get()))) {
            edited = replace(edited, version.get(), element("version", VERSION_REFERENCE));
            plugin = plugin(edited);
            version = child(edited, plugin, "version");
        }
        StringBuilder lines = new StringBuilder();
        if (version.isEmpty()) {
            lines.append(unit.repeat(4)).append(element("version", VERSION_REFERENCE)).append('\n');
        }
        if (child(edited, plugin, "extensions").isEmpty()) {
            lines.append(unit.repeat(4)).append("<extensions>true</extensions>\n");
        }
        if (!lines.isEmpty()) {
            Element after = version.isPresent() ? version.get() : child(edited, plugin, "artifactId").orElseThrow();
            edited = insert(edited, lineEnd(edited, after.end()), lines.toString());
        }
        return edited;
    }

    // ---- to the parent style ----

    private static String toParent(String pom) {
        String version = vidocqVersion(pom);
        String unit = indentUnit(pom);
        String edited = pom;
        Optional<Element> parent = topLevel(edited, "parent");
        if (parent.isPresent() && !isVidocqParent(edited, parent.get())) {
            throw new IllegalArgumentException("the pom inherits " + coordinate(edited, parent.get())
                    + ": it cannot also inherit " + PARENT_ARTIFACT + "; keep that parent and convert to the"
                    + " parentless style instead");
        }
        if (parent.isEmpty()) {
            Element modelVersion = topLevel(edited, "modelVersion").orElseThrow(() -> refused("no <modelVersion>"));
            edited = insert(edited, lineEnd(edited, modelVersion.end()), "\n" + unit + "<parent>\n"
                    + unit + unit + element("groupId", RUNTIME_GROUP) + "\n"
                    + unit + unit + element("artifactId", PARENT_ARTIFACT) + "\n"
                    + unit + unit + element("version", version) + "\n"
                    + unit + unit + "<relativePath/>\n"
                    + unit + "</parent>\n");
        }
        edited = withoutManagedBoms(edited);
        edited = withoutPluginExtension(edited);
        edited = withoutParentProperties(edited);
        return edited;
    }

    /** The BOM and the parent's JUnit BOM out of {@code <dependencyManagement>}, which goes when nothing is left. */
    private static String withoutManagedBoms(String pom) {
        Optional<Element> management = topLevel(pom, "dependencyManagement");
        if (management.isEmpty()) {
            return pom;
        }
        String edited = pom;
        for (String[] bom : new String[][] {{RUNTIME_GROUP, BOM_ARTIFACT}, {"org.junit", "junit-bom"}}) {
            Element dependencies = child(edited, topLevel(edited, "dependencyManagement").orElseThrow(), "dependencies")
                    .orElse(null);
            if (dependencies == null) {
                break;
            }
            Optional<Element> dependency = dependency(edited, dependencies, bom[0], bom[1]);
            if (dependency.isPresent() && (!"junit-bom".equals(bom[1])
                    || ProjectScaffolder.JUNIT_BOM_VERSION.equals(childText(edited, dependency.get(), "version")))) {
                edited = removeLines(edited, dependency.get());
            }
        }
        Element remaining = topLevel(edited, "dependencyManagement").orElseThrow();
        Optional<Element> dependencies = child(edited, remaining, "dependencies");
        if (dependencies.isEmpty() || children(edited, dependencies.get()).isEmpty()) {
            edited = removeLines(edited, remaining);
        }
        return edited;
    }

    /** The plugin's version and extension switch, which the parent manages. */
    private static String withoutPluginExtension(String pom) {
        String edited = pom;
        for (String name : new String[] {"version", "extensions"}) {
            Optional<Element> element = child(edited, plugin(edited), name);
            if (element.isPresent()) {
                edited = removeLines(edited, element.get());
            }
        }
        return edited;
    }

    /** The properties the parent sets, and {@code vidocq.version} when nothing reads it any more. */
    private static String withoutParentProperties(String pom) {
        String edited = pom;
        for (String[] property : new String[][] {{RELEASE_PROPERTY, ProjectScaffolder.JAVA_RELEASE},
                {M2E_APT_PROPERTY, "jdt_apt"}}) {
            Optional<Element> element = property(edited, property[0]);
            if (element.isPresent() && property[1].equals(text(edited, element.get()))) {
                edited = removeLines(edited, element.get());
            }
        }
        Optional<Element> version = property(edited, VERSION_PROPERTY);
        if (version.isPresent() && !edited.contains(VERSION_REFERENCE)) {
            edited = removeLines(edited, version.get());
        }
        return edited;
    }

    private static Optional<Element> property(String pom, String name) {
        return topLevel(pom, "properties").flatMap(p -> child(pom, p, name));
    }

    // ---- what the pom says ----

    /**
     * The Vidocq version the pom builds on: the {@code vidocq.version} property, the Vidocq parent's version, the
     * plugin's, the BOM import's, or the core dependency's, whichever comes first.
     */
    private static String vidocqVersion(String pom) {
        Element plugin = plugin(pom);
        Optional<String> property = topLevel(pom, "properties").flatMap(p -> child(pom, p, VERSION_PROPERTY))
                .map(e -> text(pom, e)).filter(PomConverter::literal);
        if (property.isPresent()) {
            return property.get();
        }
        Optional<Element> parent = topLevel(pom, "parent").filter(p -> isVidocqParent(pom, p));
        if (parent.isPresent()) {
            return childText(pom, parent.get(), "version");
        }
        String pluginVersion = childText(pom, plugin, "version");
        if (literal(pluginVersion)) {
            return pluginVersion;
        }
        Optional<Element> bom = topLevel(pom, "dependencyManagement")
                .flatMap(m -> child(pom, m, "dependencies"))
                .flatMap(d -> dependency(pom, d, RUNTIME_GROUP, BOM_ARTIFACT));
        if (bom.isPresent() && literal(childText(pom, bom.get(), "version"))) {
            return childText(pom, bom.get(), "version");
        }
        Optional<Element> core = topLevel(pom, "dependencies")
                .flatMap(d -> dependency(pom, d, RUNTIME_GROUP, "vidocq-runtime-core"));
        if (core.isPresent() && literal(childText(pom, core.get(), "version"))) {
            return childText(pom, core.get(), "version");
        }
        throw refused("no Vidocq version to be found: no " + VERSION_PROPERTY + " property, no " + PARENT_ARTIFACT
                + ", no version on " + PLUGIN_ARTIFACT + ", " + BOM_ARTIFACT + " or vidocq-runtime-core");
    }

    private static boolean literal(String version) {
        return version != null && !version.isBlank() && !version.contains("${");
    }

    private static boolean isVidocqParent(String pom, Element parent) {
        return PARENT_ARTIFACT.equals(childText(pom, parent, "artifactId"))
                && RUNTIME_GROUP.equals(childText(pom, parent, "groupId"));
    }

    private static String coordinate(String pom, Element element) {
        return childText(pom, element, "groupId") + ":" + childText(pom, element, "artifactId");
    }

    /** The Vidocq plugin under {@code <build><plugins>}, without which the pom is not an application's. */
    private static Element plugin(String pom) {
        return topLevel(pom, "build").flatMap(b -> child(pom, b, "plugins"))
                .flatMap(plugins -> children(pom, plugins).stream()
                        .filter(p -> "plugin".equals(p.name()))
                        .filter(p -> PLUGIN_ARTIFACT.equals(childText(pom, p, "artifactId")))
                        .findFirst())
                .orElseThrow(() -> refused("it does not declare " + RUNTIME_GROUP + ":" + PLUGIN_ARTIFACT
                        + " under <build><plugins>"));
    }

    private static Optional<Element> dependency(String pom, Element dependencies, String groupId, String artifactId) {
        return children(pom, dependencies).stream()
                .filter(d -> "dependency".equals(d.name()))
                .filter(d -> artifactId.equals(childText(pom, d, "artifactId"))
                        && groupId.equals(childText(pom, d, "groupId")))
                .findFirst();
    }

    private static IllegalArgumentException refused(String why) {
        return new IllegalArgumentException(NOT_RECOGNISED + why);
    }

    // ---- the text ----

    private static String element(String name, String value) {
        return "<" + name + ">" + value + "</" + name + ">";
    }

    /** The indentation of one level, read from the {@code <modelVersion>} line. */
    private static String indentUnit(String pom) {
        Optional<Element> modelVersion = topLevel(pom, "modelVersion");
        if (modelVersion.isEmpty()) {
            return DEFAULT_INDENT;
        }
        int start = lineStart(pom, modelVersion.get().start());
        String indent = pom.substring(start, modelVersion.get().start());
        return indent.isBlank() && !indent.isEmpty() ? indent : DEFAULT_INDENT;
    }

    private static int lineStart(String text, int position) {
        return text.lastIndexOf('\n', position - 1) + 1;
    }

    private static int lineEnd(String text, int position) {
        int newline = text.indexOf('\n', position);
        return newline < 0 ? text.length() : newline + 1;
    }

    private static String insert(String text, int at, String block) {
        return text.substring(0, at) + block + text.substring(at);
    }

    private static String replace(String text, Element element, String replacement) {
        return text.substring(0, element.start()) + replacement + text.substring(element.end());
    }

    /** {@code text} without the lines of {@code element}; two blank lines left touching become one. */
    private static String removeLines(String text, Element element) {
        int from = lineStart(text, element.start());
        int to = lineEnd(text, element.end());
        String edited = text.substring(0, from) + text.substring(to);
        if (from > 0 && from < edited.length() && edited.charAt(from) == '\n' && edited.charAt(from - 1) == '\n') {
            edited = edited.substring(0, from) + edited.substring(from + 1);
        }
        return edited;
    }

    // ---- a light reading of the XML: elements and their places in the text ----

    /** An element: where it starts and ends in the text, and where its content lies (none when self-closing). */
    record Element(String name, int start, int end, int contentStart, int contentEnd) {}

    private static Optional<Element> topLevel(String pom, String name) {
        Element project = children(pom, 0, pom.length()).stream().filter(e -> "project".equals(e.name())).findFirst()
                .orElseThrow(() -> refused("no <project> element"));
        return child(pom, project, name);
    }

    private static Optional<Element> child(String pom, Element parent, String name) {
        return children(pom, parent).stream().filter(e -> name.equals(e.name())).findFirst();
    }

    private static String childText(String pom, Element parent, String name) {
        return child(pom, parent, name).map(e -> text(pom, e)).orElse(null);
    }

    private static String text(String pom, Element element) {
        return element.contentStart() < 0 ? "" : pom.substring(element.contentStart(), element.contentEnd()).strip();
    }

    private static List<Element> children(String pom, Element parent) {
        return parent.contentStart() < 0 ? List.of() : children(pom, parent.contentStart(), parent.contentEnd());
    }

    /** The elements directly inside {@code [from, to)}, comments and processing instructions skipped. */
    static List<Element> children(String xml, int from, int to) {
        List<Element> elements = new ArrayList<>();
        int depth = 0;
        String openName = null;
        int openStart = -1;
        int openContentStart = -1;
        int i = from;
        while (i < to) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt >= to) {
                break;
            }
            if (xml.startsWith("<!--", lt)) {
                i = skip(xml, lt, "-->");
            } else if (xml.startsWith("<?", lt)) {
                i = skip(xml, lt, "?>");
            } else if (xml.startsWith("<![CDATA[", lt)) {
                i = skip(xml, lt, "]]>");
            } else if (xml.startsWith("</", lt)) {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) {
                    break;
                }
                depth--;
                if (depth == 0 && openName != null) {
                    elements.add(new Element(openName, openStart, gt + 1, openContentStart, lt));
                    openName = null;
                }
                i = gt + 1;
            } else {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) {
                    break;
                }
                boolean selfClosing = xml.charAt(gt - 1) == '/';
                int nameEnd = lt + 1;
                while (nameEnd < gt && !Character.isWhitespace(xml.charAt(nameEnd)) && xml.charAt(nameEnd) != '/') {
                    nameEnd++;
                }
                if (depth == 0) {
                    if (selfClosing) {
                        elements.add(new Element(xml.substring(lt + 1, nameEnd), lt, gt + 1, -1, -1));
                    } else {
                        openName = xml.substring(lt + 1, nameEnd);
                        openStart = lt;
                        openContentStart = gt + 1;
                    }
                }
                if (!selfClosing) {
                    depth++;
                }
                i = gt + 1;
            }
        }
        return elements;
    }

    private static int skip(String xml, int from, String closing) {
        int end = xml.indexOf(closing, from);
        return end < 0 ? xml.length() : end + closing.length();
    }
}
