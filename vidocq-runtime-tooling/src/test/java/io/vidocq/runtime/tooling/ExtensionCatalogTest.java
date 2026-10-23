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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI's extension catalog comes from the extensions themselves (vidocq#314): each one ships
 * {@value KnownExtensions#DESCRIPTOR}, and this test rebuilds {@value KnownExtensions#CATALOG_RESOURCE} from the
 * reactor's sources, so the committed catalog cannot drift from them.
 */
class ExtensionCatalogTest {

    private static final Path EXTENSIONS = Path.of("..", "vidocq-runtime-extensions");
    private static final Path COMMITTED = Path.of("src", "main", "resources", KnownExtensions.CATALOG_RESOURCE);
    private static final String REGENERATE =
            "./mvnw -pl vidocq-runtime-tooling test -Dtest=ExtensionCatalogTest -Dvidocq.catalog.write=true";
    private static final String GROUP_ID = "project/groupId";
    private static final String ARTIFACT_ID = "project/artifactId";
    private static final String PARENT_GROUP_ID = "project/parent/groupId";
    private static final Pattern MODULE = Pattern.compile("\\bmodule\\s+([\\w.]+)\\s*\\{");

    @Test
    void theCommittedCatalogIsTheOneTheExtensionsDescribe() throws IOException {
        String expected = render(EXTENSIONS);
        if (Boolean.getBoolean("vidocq.catalog.write")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, expected);
        }

        assertTrue(Files.isRegularFile(COMMITTED), COMMITTED + " is missing; generate it with " + REGENERATE);
        assertEquals(expected, Files.readString(COMMITTED),
                "an extension descriptor changed; regenerate the catalog with " + REGENERATE);
    }

    @Test
    void everyCodegenBundleIsAModuleOfTheReactor() throws IOException {
        Properties catalog = new Properties();
        catalog.load(new StringReader(render(EXTENSIONS)));
        for (String key : catalog.stringPropertyNames()) {
            if (key.endsWith(".codegen")) {
                String bundle = catalog.getProperty(key);
                try (var modules = Files.walk(EXTENSIONS, 2)) {
                    assertTrue(modules.anyMatch(p -> p.getFileName().toString().equals(bundle)),
                            key + " names " + bundle + ", which is no module of " + EXTENSIONS);
                }
            }
        }
    }

    /**
     * The catalog the reactor's extension descriptors describe: per extension id, its Maven coordinates and
     * Java module, read from its pom and {@code module-info.java}, then the descriptor's own keys, sorted.
     */
    static String render(Path extensions) throws IOException {
        Map<String, Map<String, String>> entries = new TreeMap<>();
        Path source = Path.of("src", "main", "resources", KnownExtensions.DESCRIPTOR);
        try (var descriptors = Files.walk(extensions, 8)) {
            for (Path descriptor : descriptors.filter(p -> p.endsWith(source)).sorted().toList()) {
                Path module = descriptor;
                for (int i = 0; i < source.getNameCount(); i++) {
                    module = module.getParent();
                }
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(descriptor)) {
                    props.load(in);
                }
                String id = props.getProperty("id");
                assertNotNull(id, descriptor + " declares no id");
                Map<String, String> entry = new TreeMap<>();
                Map<String, String> pom = topLevel(module.resolve("pom.xml"));
                entry.put("groupId", pom.getOrDefault(GROUP_ID, pom.get(PARENT_GROUP_ID)));
                entry.put("artifactId", pom.get(ARTIFACT_ID));
                entry.put("module", moduleName(module.resolve("src/main/java/module-info.java")));
                props.stringPropertyNames().stream().filter(k -> !k.equals("id"))
                        .forEach(k -> entry.put(k, props.getProperty(k).strip()));
                assertNull(entries.put(id, entry), "two extensions declare the id " + id);
            }
        }
        StringBuilder out = new StringBuilder()
                .append("# Generated from each extension's ").append(KnownExtensions.DESCRIPTOR)
                .append(" by ExtensionCatalogTest: do not edit.\n")
                .append("# Regenerate: ").append(REGENERATE).append('\n');
        // Coordinates and module first, then the descriptor's keys in order.
        entries.forEach((id, entry) -> {
            out.append('\n');
            for (String key : new String[] {"groupId", "artifactId", "module"}) {
                out.append(id).append('.').append(key).append(" = ").append(entry.remove(key)).append('\n');
            }
            entry.forEach((key, value) -> out.append(id).append('.').append(key).append(" = ").append(value)
                    .append('\n'));
        });
        return out.toString();
    }

    /** The project's groupId, its parent's and its artifactId, keyed by their path in the pom. */
    private static Map<String, String> topLevel(Path pom) throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (InputStream in = Files.newInputStream(pom)) {
            XMLStreamReader xml = XMLInputFactory.newFactory().createXMLStreamReader(in);
            Deque<String> path = new ArrayDeque<>();
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    path.addLast(xml.getLocalName());
                    String at = String.join("/", path);
                    if (at.equals(GROUP_ID) || at.equals(ARTIFACT_ID) || at.equals(PARENT_GROUP_ID)) {
                        // getElementText consumes the end tag.
                        found.put(at, xml.getElementText().strip());
                        path.removeLast();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    path.removeLast();
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("cannot read " + pom, e);
        }
        return found;
    }

    private static String moduleName(Path moduleInfo) throws IOException {
        String source = Files.readString(moduleInfo).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Matcher m = MODULE.matcher(source);
        assertTrue(m.find(), moduleInfo + " declares no module");
        return m.group(1);
    }
}
