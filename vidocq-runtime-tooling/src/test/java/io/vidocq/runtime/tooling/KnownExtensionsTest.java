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

    private static final Path EXTENSIONS = Path.of("..", "vidocq-runtime-extensions");

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
    void cyranoRestClientShipsACodegenBundle() {
        // BUG-20261008-01: without Cyrano's extension on the processor path, no
        // @RestClient bean exists and every @Inject @RestClient point is unsatisfied.
        assertEquals(java.util.Optional.of(new ExtensionCoordinate(
                        "io.vidocq.runtime.extensions.microprofile",
                        "vidocq-runtime-cyrano-rest-client-extension-codegen")),
                KnownExtensions.codegenBundle("cyrano-rest-client"));
    }

    @Test
    void foyServletShipsACodegenBundle() {
        // Without Foy's extensions on the processor path, @WebServlet classes are no beans and the
        // session context is never registered (the runtime skips extensions the processor ran).
        assertEquals(java.util.Optional.of(new ExtensionCoordinate(
                        "io.vidocq.runtime.extensions.jakartaee.web",
                        "vidocq-runtime-foy-servlet-extension-codegen")),
                KnownExtensions.codegenBundle("foy-servlet"));
        assertEquals("vidocq-runtime-foy-servlet-extension", KnownExtensions.resolve("foy-servlet").artifactId());
    }

    /** vidocq#232: the module compiles its pages into itself, so it requires the Ibarra and Peano APIs directly. */
    @Test
    void ibarraPagesRequiresTheApisItsCompiledPagesUse() {
        assertEquals("io.vidocq.runtime.extensions.jakartaee.web", KnownExtensions.resolve("ibarra-pages").groupId());
        assertEquals("vidocq-runtime-ibarra-pages-extension", KnownExtensions.resolve("ibarra-pages").artifactId());
        assertEquals(java.util.Optional.empty(), KnownExtensions.codegenBundle("ibarra-pages"));
        assertEquals(List.of("requires io.vidocq.runtime.extensions.jakartaee.web.ibarra",
                        "requires io.vidocq.ibarra.api", "requires io.vidocq.peano.api"),
                KnownExtensions.moduleDirectives("ibarra-pages", "consumer"));
    }

    /** vidocq#297: the java: namespace needs no codegen and no directive beyond its module. */
    @Test
    void namingIsAWebExtensionWithoutCodegen() {
        assertEquals("io.vidocq.runtime.extensions.jakartaee.web", KnownExtensions.resolve("naming").groupId());
        assertEquals("vidocq-runtime-naming-extension", KnownExtensions.resolve("naming").artifactId());
        assertEquals(java.util.Optional.empty(), KnownExtensions.codegenBundle("naming"));
        assertEquals(List.of("requires io.vidocq.runtime.extensions.jakartaee.web.naming"),
                KnownExtensions.moduleDirectives("naming", "consumer"));
    }

    @Test
    void peanoElShipsItsAccessorCodegenBundle() {
        assertEquals("io.vidocq.runtime.extensions.jakartaee.core",KnownExtensions.resolve("peano-el").groupId());
        assertEquals("vidocq-runtime-peano-el-extension",KnownExtensions.resolve("peano-el").artifactId());
        assertEquals(java.util.Optional.of(new ExtensionCoordinate(
                "io.vidocq.runtime.extensions.jakartaee.core","vidocq-runtime-peano-el-extension-codegen")),
                KnownExtensions.codegenBundle("peano-el"));
        assertEquals(List.of("requires io.vidocq.runtime.extensions.jakartaee.core.peano","requires io.vidocq.peano.api"),
                KnownExtensions.moduleDirectives("peano-el","consumer"));
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

    /**
     * BUG-20261024-04: no Vidocq extension lives under the {@code io.vidocq.runtime} groupId, so the former naming
     * convention for an unknown id always wrote a coordinate that does not exist.
     */
    @Test
    void rejectsAnUnknownShortIdAndNamesTheClosestOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> KnownExtensions.resolve("mansart-persistence"));

        assertEquals("not in the Vidocq catalog. Did you mean: mansart-data, mansart-pool, mansart-transactions?"
                + " An extension outside the catalog is added as groupId:artifactId.", e.getMessage());
        assertFalse(KnownExtensions.isKnown("mansart-persistence"));
    }

    @Test
    void anUnknownShortIdWithNothingCloseOnlySaysHowToAddAnyExtension() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> KnownExtensions.resolve("zzz"));

        assertEquals("not in the Vidocq catalog. An extension outside the catalog is added as groupId:artifactId.",
                e.getMessage());
    }

    @Test
    void aTypoInAShortIdNamesTheIdItWasMeantToBe() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> KnownExtensions.resolve("knock-helth"));

        assertTrue(e.getMessage().contains("Did you mean: knock-health?"), e.getMessage());
    }

    /** BUG-20261024-03: mansart-data is Jakarta Data, not Jakarta Persistence. */
    @Test
    void mansartDataIsDescribedAsJakartaData() {
        assertEquals("Jakarta Data 1.0 repositories", KnownExtensions.byId("mansart-data").orElseThrow().description());
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
        assertTrue(missing.isEmpty(), "published extensions without a " + KnownExtensions.DESCRIPTOR + ": " + missing);
    }

    /** BUG-20261024-04: a module directory its group's pom does not list is neither built nor published. */
    @Test
    void everyExtensionModuleDirectoryIsInTheReactor() throws IOException {
        Set<String> orphans = new TreeSet<>();
        try (var groups = Files.list(EXTENSIONS)) {
            for (var group : groups.filter(g -> Files.isRegularFile(g.resolve("pom.xml"))).toList()) {
                String modules = Files.readString(group.resolve("pom.xml"));
                try (var children = Files.list(group)) {
                    children.filter(m -> Files.isRegularFile(m.resolve("pom.xml")))
                            .map(m -> m.getFileName().toString())
                            .filter(name -> !modules.contains("<module>" + name + "</module>"))
                            .forEach(orphans::add);
                }
            }
        }
        assertTrue(orphans.isEmpty(), "module directories outside the reactor: " + orphans);
    }

    /**
     * BUG-20261024-05: an application declares a published extension, its codegen bundle or its {@code -dev}
     * module without a version, so the runtime parent manages each of them.
     */
    @Test
    void theRuntimeParentManagesEveryPublishedExtensionModule() throws IOException {
        String parent = Files.readString(Path.of("..", "pom.xml"));
        String managed = parent.substring(parent.indexOf("<dependencyManagement>"),
                parent.indexOf("</dependencyManagement>"));
        Set<String> unmanaged = new TreeSet<>();
        for (String module : publishedModules()) {
            if (module.matches(".*-extension(-codegen|-dev)?")
                    && !managed.contains("<artifactId>" + module + "</artifactId>")) {
                unmanaged.add(module);
            }
        }
        assertTrue(unmanaged.isEmpty(), "published modules the runtime parent does not manage: " + unmanaged);
    }

    /** BUG-20261024-04: an extension whose codegen bundle its descriptor omits is added without it. */
    @Test
    void everyExtensionDeclaresItsCodegenSibling() throws IOException {
        Set<String> modules = publishedModules();
        for (RegistryEntry e : KnownExtensions.catalog()) {
            String sibling = e.coordinate().artifactId() + "-codegen";
            if (modules.contains(sibling)) {
                assertEquals(sibling, KnownExtensions.codegenBundle(e.id()).map(ExtensionCoordinate::artifactId)
                        .orElse(null), e.id() + " does not declare its codegen bundle");
            }
        }
    }

    /** The reactor modules under {@link #EXTENSIONS} a release deploys. */
    private static Set<String> publishedModules() throws IOException {
        Set<String> published = new TreeSet<>();
        try (var groups = Files.list(EXTENSIONS)) {
            for (var group : groups.filter(g -> Files.isRegularFile(g.resolve("pom.xml"))).toList()) {
                String modules = Files.readString(group.resolve("pom.xml"));
                try (var children = Files.list(group)) {
                    for (var module : children.toList()) {
                        String name = module.getFileName().toString();
                        Path pom = module.resolve("pom.xml");
                        if (modules.contains("<module>" + name + "</module>") && Files.isRegularFile(pom)
                                && !Files.readString(pom).contains("<maven.deploy.skip>true")) {
                            published.add(name);
                        }
                    }
                }
            }
        }
        return published;
    }

    @Test
    void everyCatalogEntryNamesItsModule() {
        for (RegistryEntry e : KnownExtensions.catalog()) {
            assertTrue(KnownExtensions.moduleName(e.id()).isPresent(), e.id() + " has no module name");
        }
    }

    /** vidocq#314: the catalog is built from the extensions' descriptors, so it has no hand-picked order. */
    @Test
    void catalogIsSortedById() {
        List<String> ids = KnownExtensions.catalog().stream().map(RegistryEntry::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
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

    /** Vidocq/vidocq#353: {@code vidocq extension list --available} shows the catalog by family. */
    @Test
    void theCatalogIsGroupedByFamilyInTheOrderOfTheFamilies() {
        var byFamily = KnownExtensions.catalogByFamily();

        assertEquals(List.of(ExtensionFamily.values()), List.copyOf(byFamily.keySet()));
        assertEquals(KnownExtensions.catalog().size(), byFamily.values().stream().mapToInt(List::size).sum());
        assertTrue(byFamily.get(ExtensionFamily.MICROPROFILE).stream().anyMatch(e -> e.id().equals("knock-health")));
        assertTrue(byFamily.get(ExtensionFamily.DATA).stream().anyMatch(e -> e.id().equals("mansart-data")));
        assertTrue(byFamily.get(ExtensionFamily.WEB).stream().anyMatch(e -> e.id().equals("chappe-webserver")));
        assertTrue(byFamily.get(ExtensionFamily.JAKARTA_EE).stream().anyMatch(e -> e.id().equals("cassini-rest")));
    }

    @Test
    void aFamilyIsReadFromItsDescriptorId() {
        assertEquals(ExtensionFamily.JAKARTA_EE, ExtensionFamily.of("jakarta-ee"));
        assertEquals("MicroProfile", ExtensionFamily.MICROPROFILE.title());
        var e = assertThrows(IllegalArgumentException.class, () -> ExtensionFamily.of("tools"));
        assertEquals("unknown extension family 'tools': one of jakarta-ee, microprofile, data, web", e.getMessage());
    }

    /** Telemetry with the JDBC pool brings the glue that turns statements into spans (BUG-20261030-25). */
    @Test
    void theJdbcSpansGlueGoesWithTelemetryAndThePool() {
        assertEquals(List.of("jdbc-telemetry"),
                KnownExtensions.companions(Set.of("humboldt-telemetry", "mansart-pool", "cassini-rest")));
        assertEquals(List.of(), KnownExtensions.companions(Set.of("humboldt-telemetry")));
        assertEquals(List.of(), KnownExtensions.companions(
                Set.of("humboldt-telemetry", "mansart-pool", "jdbc-telemetry")));
    }
}
