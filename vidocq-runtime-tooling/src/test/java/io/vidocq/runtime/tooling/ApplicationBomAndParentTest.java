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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#339: an application takes its parent and its versions from {@code vidocq-runtime-parent} and
 * {@code vidocq-runtime-bom}, not from the reactor's own root, which carries the CI, SonarQube and TCK settings of
 * this repository.
 */
class ApplicationBomAndParentTest {

    private static final Path ROOT = Path.of("..");
    private static final String RUNTIME_GROUP = "io.vidocq.runtime";

    /** Published modules an application never declares as a dependency. */
    private static final Set<String> NOT_A_DEPENDENCY = Set.of(
            "vidocq-runtime-bom", "vidocq-runtime-parent", "vidocq-runtime-maven-plugin");

    @Test
    void theReactorRootIsTheBuildOfThisRepositoryNotTheApplicationParent() throws Exception {
        Element root = pom(ROOT).getDocumentElement();

        assertEquals("vidocq-runtime-build", child(root, "artifactId"));
        assertTrue(modules(ROOT).containsAll(List.of("vidocq-runtime-bom", "vidocq-runtime-parent")),
                "the reactor builds and publishes the BOM and the application parent");
    }

    @Test
    void theBomManagesEveryPublishedArtifactAtTheReactorVersion() throws Exception {
        String version = reactorVersion();
        Map<String, String> managed = managedVersions(pom(ROOT.resolve("vidocq-runtime-bom")));

        Set<String> missing = new TreeSet<>();
        for (String artifact : publishedArtifacts()) {
            if (!NOT_A_DEPENDENCY.contains(artifact.substring(artifact.indexOf(':') + 1))
                    && !version.equals(managed.get(artifact))) {
                missing.add(artifact);
            }
        }
        assertTrue(missing.isEmpty(), "published artifacts the BOM does not manage at " + version + ": " + missing);
    }

    @Test
    void theBomManagesNothingButTheRuntimeArtifacts() throws Exception {
        Set<String> published = publishedArtifacts();
        Set<String> foreign = new TreeSet<>(managedVersions(pom(ROOT.resolve("vidocq-runtime-bom"))).keySet());
        foreign.removeAll(published);

        assertTrue(foreign.isEmpty(), "the BOM manages artifacts this reactor does not publish: " + foreign);
    }

    @Test
    void theApplicationParentImportsTheBomAndWiresTheBuildOfAnApplication() throws Exception {
        String version = reactorVersion();
        Path dir = ROOT.resolve("vidocq-runtime-parent");
        Element project = pom(dir).getDocumentElement();
        String text = Files.readString(dir.resolve("pom.xml"));

        Element bom = elements(project, "dependencyManagement", "dependencies", "dependency").stream()
                .filter(d -> "vidocq-runtime-bom".equals(child(d, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("the parent does not import the BOM"));
        assertEquals("import", child(bom, "scope"));
        assertEquals("pom", child(bom, "type"));
        assertEquals(version, child(bom, "version"));

        Element plugin = elements(project, "build", "pluginManagement", "plugins", "plugin").stream()
                .filter(p -> "vidocq-runtime-maven-plugin".equals(child(p, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("the parent does not manage the Vidocq plugin"));
        assertEquals(version, child(plugin, "version"));
        for (String goal : List.of("checkpom", "check-module-info", "package")) {
            assertTrue(text.contains("<goal>" + goal + "</goal>"), "the Vidocq plugin does not run " + goal);
        }
        assertTrue(text.contains("<artifactId>vidocq-runtime-core-codegen</artifactId>"),
                "the compiler does not run the Vauban indexer");
        assertTrue(text.contains("<arg>-Avauban.providesHint=false</arg>"),
                "the Vauban processor advises a provides the application layer makes (BUG-20261024-10)");
    }

    /**
     * BUG-20261025-01: a plugin managed twice makes Maven warn on every build of every application ("must be unique
     * but found duplicate declaration"), and the deploy batching of this repository's releases is no setting of an
     * application: the parent applies it to its own deploy only.
     */
    @Test
    void theApplicationParentManagesEachPluginOnceAndKeepsItsDeploySettingsToItself() throws Exception {
        Element project = pom(ROOT.resolve("vidocq-runtime-parent")).getDocumentElement();

        List<String> managed = elements(project, "build", "pluginManagement", "plugins", "plugin").stream()
                .map(p -> child(p, "artifactId")).toList();
        assertEquals(List.copyOf(new java.util.LinkedHashSet<>(managed)), managed, "a plugin managed twice");
        for (Element plugin : elements(project, "build", "pluginManagement", "plugins", "plugin")) {
            assertFalse(plugin.getTextContent().contains("deployAtEnd"),
                    "applications inherit deployAtEnd from " + child(plugin, "artifactId"));
        }
        Element deploy = elements(project, "build", "plugins", "plugin").stream()
                .filter(p -> "maven-deploy-plugin".equals(child(p, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("the parent's own deploy is not batched"));
        assertEquals("false", child(deploy, "inherited"));
        assertTrue(deploy.getTextContent().contains("true"), "deployAtEnd");
    }

    @Test
    void theApplicationParentCarriesNoSettingOfThisRepository() throws Exception {
        String text = Files.readString(ROOT.resolve("vidocq-runtime-parent").resolve("pom.xml"));

        for (String setting : List.of("ci.tck.modules", "sonar.", "langchain4j", "testcontainers", "arquillian",
                "<profile>", "<module>")) {
            assertFalse(text.contains(setting), "the application parent carries " + setting);
        }
    }

    // -------------------------------------------------------------------------

    private static String reactorVersion() throws Exception {
        return child(pom(ROOT).getDocumentElement(), "version");
    }

    /** {@code groupId:artifactId} of every module a release deploys: no aggregator, nothing under a skip. */
    private static Set<String> publishedArtifacts() throws Exception {
        Set<String> published = new TreeSet<>();
        collect(ROOT, published);
        return published;
    }

    private static void collect(Path dir, Set<String> published) throws Exception {
        String text = Files.readString(dir.resolve("pom.xml"));
        if (text.contains("<maven.deploy.skip>true</maven.deploy.skip>")) {
            return;
        }
        List<String> modules = modules(dir);
        if (modules.isEmpty()) {
            Element project = pom(dir).getDocumentElement();
            String groupId = child(project, "groupId");
            if (groupId == null) {
                groupId = child(elements(project, "parent").getFirst(), "groupId");
            }
            if (groupId.startsWith(RUNTIME_GROUP)) {
                published.add(groupId + ":" + child(project, "artifactId"));
            }
        }
        for (String module : modules) {
            collect(dir.resolve(module), published);
        }
    }

    /** The modules of the pom in {@code dir}, outside its profiles. */
    private static List<String> modules(Path dir) throws Exception {
        List<String> modules = new ArrayList<>();
        for (Element module : elements(pom(dir).getDocumentElement(), "modules", "module")) {
            modules.add(module.getTextContent().strip());
        }
        return modules;
    }

    private static Map<String, String> managedVersions(Document pom) {
        Map<String, String> managed = new TreeMap<>();
        for (Element d : elements(pom.getDocumentElement(), "dependencyManagement", "dependencies", "dependency")) {
            managed.put(child(d, "groupId") + ":" + child(d, "artifactId"), child(d, "version"));
        }
        return managed;
    }

    private static Document pom(Path dir) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(dir.resolve("pom.xml").toFile());
    }

    /** The elements reached from {@code from} through {@code path}, one child name per step. */
    private static List<Element> elements(Element from, String... path) {
        List<Element> current = List.of(from);
        for (String name : path) {
            List<Element> next = new ArrayList<>();
            for (Element e : current) {
                for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element c && c.getTagName().equals(name)) {
                        next.add(c);
                    }
                }
            }
            current = next;
        }
        return current;
    }

    private static String child(Element parent, String name) {
        List<Element> found = elements(parent, name);
        return found.isEmpty() ? null : found.getFirst().getTextContent().strip();
    }
}
