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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static io.vidocq.runtime.maven.EnrichedJarsTest.descriptor;
import static io.vidocq.runtime.maven.EnrichedJarsTest.descriptorOf;
import static io.vidocq.runtime.maven.EnrichedJarsTest.emptyClass;
import static io.vidocq.runtime.maven.EnrichedJarsTest.patch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The enriched copy of a jar a brick processed: its output merged in, declared, and the copy stamped with it. */
class EnrichedJarsBrickTest {

    static final String WEB = "io.vidocq.foy.spi.WebComponent";
    static final String WEB_FILE = "META-INF/services/" + WEB;
    static final String INDEX = "META-INF/vidocq/class-index.list";
    static final Set<String> MERGED = Set.of(WEB_FILE, INDEX);
    static final List<String> FOY_INPUTS = List.of("brick Foy io.vidocq:foy-codegen:0.4.0 sha256:aaaa");

    @TempDir
    Path tmp;

    /** A {@code $$FoyComponent}: implements the Foy SPI and calls into another package of the jar's own module. */
    static byte[] foyComponent(String fqn) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> clb
                .withInterfaceSymbols(ClassDesc.of(WEB), ClassDesc.of("org.dep.api.Marker"),
                        ClassDesc.of("org.unresolved.Thing")));
    }

    static Function<Set<String>, Set<String>> modules(Map<String, String> moduleOfPackage) {
        return packages -> packages.stream().map(moduleOfPackage::get).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    static final Function<Set<String>, Set<String>> MODULES = modules(Map.of(
            "io.vidocq.foy.spi", "io.vidocq.foy",
            "org.dep.api", "org.dep",
            "java.lang", "java.base"));

    static Path jar(Path file, Map<String, byte[]> entries, Map<String, String> mainAttributes) throws Exception {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.forEach(manifest.getMainAttributes()::putValue);
        try (var out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String entry(Path jar, String name) throws Exception {
        try (JarFile in = new JarFile(jar.toFile())) {
            JarEntry e = in.getJarEntry(name);
            return e == null ? null : new String(in.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Attributes manifestOf(Path jar) throws Exception {
        try (JarFile in = new JarFile(jar.toFile())) {
            return in.getManifest().getMainAttributes();
        }
    }

    static EnrichedJars.Scanned foy(Path jar, String artifactId, List<String> brickInputs) {
        return new EnrichedJars.Scanned(jar, artifactId, "org.dep:" + artifactId + ":1.0", List.of("Foy"),
                brickInputs);
    }

    List<Path> enrich(Path build, EnrichedJars.Scanned dep, List<String> providers) throws Exception {
        return EnrichedJars.enrichAll(build, List.of(dep), providers, List.of(dep.originalJar()), MERGED, MODULES,
                line -> { }, line -> { });
    }

    /** The core reads this same literal in {@code StartupFacts.enrichedBy} (it cannot depend on the plugin). */
    @Test
    void theEnrichedByAttributeIsTheOneTheCoreReads() {
        org.junit.jupiter.api.Assertions.assertEquals("Vidocq-Enriched-By", EnrichedJars.ENRICHED_BY);
    }

    @Test
    void anAutomaticJarStaysAutomatic() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/flow-1.0.jar"), Map.of(
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/api/Marker.class", emptyClass("org.dep.api.Marker")),
                Map.of("Automatic-Module-Name", "org.flow"));
        patch(JpmsPatches.patchDirFor(build, "flow"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));

        List<Path> enriched = enrich(build, foy(auto, "flow", FOY_INPUTS), List.of());

        assertEquals(List.of(auto), enriched);
        Path copy = EnrichedJars.resolve(build, auto);
        assertNull(entry(copy, "module-info.class"), "no descriptor is synthesized");
        assertTrue(entry(copy, "org/dep/Servlet$$FoyComponent.class") != null);
        assertEquals("org.dep.Servlet$$FoyComponent\n", entry(copy, WEB_FILE));
        Attributes main = manifestOf(copy);
        assertEquals("Foy", main.getValue("Vidocq-Enriched-By"));
        assertEquals("org.flow", main.getValue("Automatic-Module-Name"));
        ModuleDescriptor md = descriptorOf(copy);
        assertTrue(md.isAutomatic());
        assertEquals("org.flow", md.name());
        assertEquals(List.of("org.dep.Servlet$$FoyComponent"), md.provides().stream()
                .filter(p -> p.service().equals(WEB)).findFirst().orElseThrow().providers(),
                "an automatic module provides what its service files list");
    }

    @Test
    void mergedResourcesKeepTheJarsLines() throws Exception {
        Path build = tmp.resolve("target");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("module-info.class", descriptor("org.dep"));
        entries.put("org/dep/Old.class", emptyClass("org.dep.Old"));
        entries.put("org/dep/Servlet.class", emptyClass("org.dep.Servlet"));
        entries.put(WEB_FILE, text("# shipped by the library\norg.dep.Old\n"));
        entries.put(INDEX, text("org.dep.Old\n"));
        entries.put("META-INF/notes.txt", text("the jar's\n"));
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), entries, Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Old\norg.dep.Servlet$$FoyComponent\n"),
                INDEX, text("org.dep.Servlet\n"),
                "META-INF/notes.txt", text("generated\n")));

        enrich(build, foy(lib, "lib", FOY_INPUTS), List.of());

        Path copy = EnrichedJars.resolve(build, lib);
        assertEquals("org.dep.Old\norg.dep.Servlet$$FoyComponent\n", entry(copy, WEB_FILE));
        assertEquals("org.dep.Old\norg.dep.Servlet\n", entry(copy, INDEX));
        assertEquals("the jar's\n", entry(copy, "META-INF/notes.txt"), "a resource not merged stays the jar's");
    }

    @Test
    void anExplicitModuleProvidesTheGeneratedService() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/api/Marker.class", emptyClass("org.dep.api.Marker")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));

        enrich(build, foy(lib, "lib", FOY_INPUTS), List.of());

        Path copy = EnrichedJars.resolve(build, lib);
        ModuleDescriptor md = descriptorOf(copy);
        assertFalse(md.isAutomatic());
        assertEquals(List.of("org.dep.Servlet$$FoyComponent"), md.provides().stream()
                .filter(p -> p.service().equals(WEB)).findFirst().orElseThrow().providers());
        Set<String> requires = md.requires().stream().map(ModuleDescriptor.Requires::name)
                .collect(Collectors.toSet());
        assertTrue(requires.contains("io.vidocq.foy"), requires.toString());
        assertFalse(requires.contains("org.dep"), "never itself");
        assertEquals(1, md.requires().stream().filter(r -> r.name().equals("java.base")).count());
        assertEquals("Foy", manifestOf(copy).getValue("Vidocq-Enriched-By"));
    }

    @Test
    void aModulePackagesAttributeGainsThePackagesOfTheGeneratedClasses() throws Exception {
        Path build = tmp.resolve("target");
        // As the jar tool, moditect or bnd write it: the descriptor lists the module's packages.
        byte[] withPackages = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("org.dep"), mb -> mb
                        .requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))),
                cb -> cb.with(ModulePackagesAttribute.ofNames(PackageDesc.of("org.dep"))));
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", withPackages,
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/gen/Servlet$$FoyComponent.class", foyComponent("org.dep.gen.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.gen.Servlet$$FoyComponent\n")));

        enrich(build, foy(lib, "lib", FOY_INPUTS), List.of());

        ModuleDescriptor md = descriptorOf(EnrichedJars.resolve(build, lib));
        assertEquals(Set.of("org.dep", "org.dep.gen"), md.packages());
        assertEquals(List.of("org.dep.gen.Servlet$$FoyComponent"), md.provides().stream()
                .filter(p -> p.service().equals(WEB)).findFirst().orElseThrow().providers());
    }

    /** A multi-release jar whose only descriptor is {@code META-INF/versions/9/module-info.class}. */
    Path versionedDescriptorJar() throws Exception {
        return jar(tmp.resolve("m2/mr-lib-1.0.jar"), Map.of(
                "META-INF/versions/9/module-info.class", descriptor("org.dep"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/api/Marker.class", emptyClass("org.dep.api.Marker")), Map.of("Multi-Release", "true"));
    }

    @Test
    void aMultiReleaseJarWithAVersionedDescriptorIsAnExplicitModule() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = versionedDescriptorJar();
        patch(JpmsPatches.patchDirFor(build, "mr-lib"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));

        enrich(build, foy(lib, "mr-lib", FOY_INPUTS), List.of());

        Path copy = EnrichedJars.resolve(build, lib);
        assertNull(entry(copy, "module-info.class"), "no root descriptor is added");
        ModuleDescriptor md = descriptorOf(copy);
        assertFalse(md.isAutomatic());
        assertEquals("org.dep", md.name());
        assertEquals(List.of("org.dep.Servlet$$FoyComponent"), md.provides().stream()
                .filter(p -> p.service().equals(WEB)).findFirst().orElseThrow().providers(),
                "the versioned descriptor the JDK reads declares the generated provider");
        assertTrue(md.requires().stream().anyMatch(r -> r.name().equals("io.vidocq.foy")), md::toString);
        assertFalse(ScanSelection.inspect(lib).automatic(), "the selection does not take it for automatic");
    }

    @Test
    void aVaubanOnlyCopyGetsNoRequiresFromTheBricks() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Service.class", emptyClass("org.dep.Service")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/_VaubanComponents.class", foyComponent("org.dep._VaubanComponents")));

        EnrichedJars.enrichAll(build, List.of(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0")),
                List.of("org.dep._VaubanComponents"), List.of(lib), MERGED, MODULES, line -> { }, line -> { });

        Set<String> requires = descriptorOf(EnrichedJars.resolve(build, lib)).requires().stream()
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());
        assertFalse(requires.contains("io.vidocq.foy"), "only a copy a brick processed requires for it: " + requires);
    }

    @Test
    void aBrickSelectedAutomaticJarStaysAutomaticWithVaubanProviders() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/flow-1.0.jar"), Map.of(
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/Bean.class", emptyClass("org.dep.Bean")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "flow"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents")));
        // vauban:modularize of an earlier build left a copy with a synthesized descriptor: not the source here.
        jar(build.resolve("vauban-modularized/flow-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.flow"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/Bean.class", emptyClass("org.dep.Bean")), Map.of());

        List<Path> enriched = EnrichedJars.enrichAll(build,
                List.of(new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0", true)),
                List.of("org.dep._VaubanComponents"), List.of(auto), MERGED, MODULES, line -> { }, line -> { });

        assertEquals(List.of(auto), enriched);
        Path copy = EnrichedJars.resolve(build, auto);
        assertNull(entry(copy, "module-info.class"), "no synthesized descriptor: it would miss uses");
        assertEquals("org.dep._VaubanComponents\n", entry(copy, EnrichedJars.SERVICE_FILE));
        ModuleDescriptor md = descriptorOf(copy);
        assertTrue(md.isAutomatic());
        assertEquals(List.of("org.dep._VaubanComponents"), md.provides().stream()
                .filter(p -> p.service().equals(EnrichedJarsTest.SPI)).findFirst().orElseThrow().providers());
    }

    @Test
    void vaubanAndBrickOutputShareOneCopy() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/auto-lib-1.0.jar"), Map.of(
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/api/Marker.class", emptyClass("org.dep.api.Marker")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "auto-lib"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents"),
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));

        List<Path> enriched = enrich(build, foy(auto, "auto-lib", FOY_INPUTS), List.of("org.dep._VaubanComponents"));

        assertEquals(List.of(auto), enriched);
        Path copy = EnrichedJars.resolve(build, auto);
        ModuleDescriptor md = descriptorOf(copy);
        assertTrue(md.isAutomatic(), "a brick selected it: it stays automatic, Vauban providers included");
        assertNull(entry(copy, "module-info.class"));
        Map<String, List<String>> provides = md.provides().stream()
                .collect(Collectors.toMap(ModuleDescriptor.Provides::service, ModuleDescriptor.Provides::providers));
        assertEquals(List.of("org.dep._VaubanComponents"), provides.get(EnrichedJarsTest.SPI));
        assertEquals(List.of("org.dep.Servlet$$FoyComponent"), provides.get(WEB));
        assertEquals("Foy", manifestOf(copy).getValue("Vidocq-Enriched-By"));
        try (var files = Files.list(EnrichedJars.root(build))) {
            assertEquals(1, files.count(), "one copy");
        }
    }

    @Test
    void aBrickChangeRewritesTheCopy() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent")));
        enrich(build, foy(lib, "lib", FOY_INPUTS), List.of());
        String before = manifestOf(EnrichedJars.resolve(build, lib)).getValue("Vidocq-Enriched-Inputs");

        enrich(build, foy(lib, "lib", List.of("brick Foy io.vidocq:foy-codegen:0.4.0 sha256:bbbb")), List.of());

        String after = manifestOf(EnrichedJars.resolve(build, lib)).getValue("Vidocq-Enriched-Inputs");
        assertNotEquals(before, after, "a processor change rewrites the copy");
    }

    @Test
    void theInputsDigestOfACopyWithoutBricksIsUnchanged() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Service.class", emptyClass("org.dep.Service")), Map.of());
        Path patchDir = patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents")));
        List<String> providers = List.of("org.dep._VaubanComponents");
        EnrichedJars.enrichAll(build, List.of(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0")), providers,
                List.of(lib), line -> { }, line -> { });
        Path direct = tmp.resolve("direct/lib-1.0.jar");

        EnrichedJars.write(lib, null, patchDir, providers, "org.dep:lib:1.0", lib, direct);

        assertEquals(manifestOf(direct).getValue("Vidocq-Enriched-Inputs"),
                manifestOf(EnrichedJars.resolve(build, lib)).getValue("Vidocq-Enriched-Inputs"));
        assertNull(manifestOf(direct).getValue("Vidocq-Enriched-By"));
    }

    @Test
    void atPackagingTheRebaseKeepsTheBrickOutput() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/flow-1.0.jar"), Map.of(
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/api/Marker.class", emptyClass("org.dep.api.Marker"),
                WEB_FILE, text("org.dep.Old\n")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "flow"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));
        EnrichedJars.Scanned dep = foy(auto, "flow", FOY_INPUTS);
        enrich(build, dep, List.of());
        // prepare-package: vauban:modularize writes its copy, with a descriptor of its own.
        jar(build.resolve("vauban-modularized/flow-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.flow"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet"),
                "org/dep/api/Marker.class", emptyClass("org.dep.api.Marker"),
                WEB_FILE, text("org.dep.Old\n")), Map.of());

        EnrichedJars.rebaseOnModularized(build, dep, MERGED, MODULES, line -> { });

        Path copy = EnrichedJars.resolve(build, auto);
        ModuleDescriptor md = descriptorOf(copy);
        assertEquals("org.flow", md.name());
        assertEquals(List.of("org.dep.Servlet$$FoyComponent"), md.provides().stream()
                .filter(p -> p.service().equals(WEB)).findFirst().orElseThrow().providers(),
                "the jar's own line is not a generated provider");
        assertEquals("org.dep.Old\norg.dep.Servlet$$FoyComponent\n", entry(copy, WEB_FILE));
        assertEquals("Foy", manifestOf(copy).getValue("Vidocq-Enriched-By"));
    }

    @Test
    void aRebaseThatDoesNotKnowTheBricksLeavesABrickCopyAlone() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/flow-1.0.jar"), Map.of(
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "flow"), Map.of(
                "org/dep/Servlet$$FoyComponent.class", foyComponent("org.dep.Servlet$$FoyComponent"),
                WEB_FILE, text("org.dep.Servlet$$FoyComponent\n")));
        enrich(build, foy(auto, "flow", FOY_INPUTS), List.of());
        jar(build.resolve("vauban-modularized/flow-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.flow"),
                "org/dep/Servlet.class", emptyClass("org.dep.Servlet")), Map.of());

        EnrichedJars.rebaseOnModularized(build, new EnrichedJars.Scanned(auto, "flow", "org.dep:flow:1.0"),
                line -> { });

        assertEquals("Foy", manifestOf(EnrichedJars.resolve(build, auto)).getValue("Vidocq-Enriched-By"),
                "not rebuilt without the brick output it would lose");
    }
}
