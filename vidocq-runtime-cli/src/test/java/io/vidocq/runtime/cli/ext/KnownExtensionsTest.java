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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownExtensionsTest {

    @Test
    void catalogIsNonEmptyAndWellFormed() {
        assertFalse(KnownExtensions.catalog().isEmpty());
        for (RegistryEntry e : KnownExtensions.catalog()) {
            assertFalse(e.id().isBlank());
            assertTrue(e.groupId().startsWith("io.vidocq.runtime"));
            assertTrue(e.artifactId().startsWith("vidocq-runtime-"));
            assertTrue(e.artifactId().endsWith("-extension"));
        }
    }

    @Test
    void resolvesKnownShortIdToCategorySpecificGroupId() {
        ExtensionCoordinate c = KnownExtensions.resolve("knock-health");
        assertEquals("io.vidocq.runtime.extensions.microprofile", c.groupId());
        assertEquals("vidocq-runtime-knock-health-extension", c.artifactId());
    }

    @Test
    void knowsTheHeisenbergFaultToleranceExtension() {
        // Wired into the runtime by the MicroProfile 7.1 certification PR (#19) —
        // the catalog must expose it to `extension list/add` and `create -x`.
        ExtensionCoordinate c = KnownExtensions.resolve("heisenberg-fault-tolerance");
        assertEquals("io.vidocq.runtime.extensions.microprofile", c.groupId());
        assertEquals("vidocq-runtime-heisenberg-fault-tolerance-extension", c.artifactId());
        assertTrue(KnownExtensions.isKnown("heisenberg-fault-tolerance"));
    }

    @Test
    void ravelConfigShipsACodegenBundle() {
        // ravel#21: without Ravel's extension on the processor path, every
        // @Inject @ConfigProperty point fails the compilation as unsatisfied.
        assertEquals(java.util.Optional.of(new ExtensionCoordinate(
                        "io.vidocq.runtime.extensions.microprofile",
                        "vidocq-runtime-ravel-config-extension-codegen")),
                KnownExtensions.codegenBundle("ravel-config"));
    }

    @Test
    void shortIdLookupIsCaseInsensitive() {
        assertTrue(KnownExtensions.byId("Knock-Health").isPresent());
        assertTrue(KnownExtensions.isKnown("CASSINI-REST"));
    }

    @Test
    void resolvesExplicitGav() {
        ExtensionCoordinate c = KnownExtensions.resolve("com.acme:my-ext");
        assertEquals("com.acme", c.groupId());
        assertEquals("my-ext", c.artifactId());
    }

    @Test
    void resolvesUnknownIdByConvention() {
        ExtensionCoordinate c = KnownExtensions.resolve("rest");
        assertEquals("io.vidocq.runtime", c.groupId());
        assertEquals("vidocq-runtime-rest-extension", c.artifactId());
        assertFalse(KnownExtensions.isKnown("rest"));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> KnownExtensions.resolve("  "));
    }

    /** Extensions only {@code vidocq:dev} adds: never something to declare in a pom. */
    private static final Set<String> DEV_ONLY = Set.of("vidocq-runtime-devconsole-extension");

    @Test
    void catalogCoversEveryPublishedExtensionOfTheReactor() throws IOException {
        Path extensions = Path.of("..", "vidocq-runtime-extensions");
        Set<String> published = new TreeSet<>();
        try (var groups = Files.list(extensions)) {
            for (var group : groups.filter(Files::isDirectory).toList()) {
                Path groupPom = group.resolve("pom.xml");
                if (!Files.isRegularFile(groupPom)) {
                    continue;
                }
                String modules = Files.readString(groupPom);
                try (var children = Files.list(group)) {
                    for (var module : children.toList()) {
                        String name = module.getFileName().toString();
                        Path pom = module.resolve("pom.xml");
                        if (name.endsWith("-extension") && modules.contains("<module>" + name + "</module>")
                                && Files.isRegularFile(pom)
                                && !Files.readString(pom).contains("<maven.deploy.skip>true")
                                && !DEV_ONLY.contains(name)) {
                            published.add(name);
                        }
                    }
                }
            }
        }
        Set<String> catalog = new TreeSet<>();
        KnownExtensions.catalog().forEach(e -> catalog.add(e.coordinate().artifactId()));

        assertFalse(published.isEmpty(), "no extension module found under " + extensions.toAbsolutePath());
        Set<String> missing = new TreeSet<>(published);
        missing.removeAll(catalog);
        assertTrue(missing.isEmpty(), "published extensions missing from KnownExtensions: " + missing);
    }

    @Test
    void everyCatalogEntryNamesItsModule() {
        for (RegistryEntry e : KnownExtensions.catalog()) {
            assertTrue(KnownExtensions.moduleName(e.id()).isPresent(), e.id() + " has no module name");
        }
    }

    @Test
    void cassiniRestNeedsWhatItsResourcesUseAndOpensTheApplicationPackage() {
        List<String> directives = KnownExtensions.moduleDirectives("cassini-rest", "com.acme.todo");

        assertTrue(directives.containsAll(List.of("requires static java.compiler", "requires jakarta.ws.rs",
                "requires jakarta.json.bind", "requires io.vidocq.cassini.api",
                "requires io.vidocq.runtime.extensions.jakartaee.core.cassini", "opens com.acme.todo")));
        assertFalse(KnownExtensions.moduleDirectives("cassini-rest", null).stream().anyMatch(d -> d.startsWith("opens")),
                "no package to open, no opens");
        assertEquals(List.of("requires io.vidocq.runtime.extensions.microprofile.knock"),
                KnownExtensions.moduleDirectives("knock-health", "com.acme.todo"));
        assertEquals(List.of(), KnownExtensions.moduleDirectives("com.acme:unknown", "com.acme.todo"));
    }

    @Test
    void moduleNamesMatchTheReactorModuleInfos() throws IOException {
        Path extensions = Path.of("..", "vidocq-runtime-extensions");
        for (RegistryEntry e : KnownExtensions.catalog()) {
            String artifact = e.coordinate().artifactId();
            Path found;
            try (var walk = Files.walk(extensions, 2)) {
                found = walk.filter(p -> p.getFileName().toString().equals(artifact)).findFirst().orElseThrow();
            }
            Path moduleInfo;
            try (var walk = Files.walk(found.resolve("src/main"))) {
                moduleInfo = walk.filter(p -> p.getFileName().toString().equals("module-info.java")).findFirst().orElseThrow();
            }
            String src = Files.readString(moduleInfo);
            assertTrue(src.matches("(?s).*\\bmodule\\s+" + KnownExtensions.moduleName(e.id()).orElseThrow().replace(".", "\\.") + "\\s*\\{.*"),
                    e.id() + ": module name differs from " + moduleInfo);
        }
    }
}
