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
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichedJarsTest {

    static final String SPI = "io.vidocq.vauban.api.VaubanComponentProvider";

    @TempDir
    Path tmp;

    static byte[] descriptor(String module) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), mb -> mb.requires(
                ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
    }

    static byte[] emptyClass(String fqn) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> { });
    }

    /** A class whose method names a type of io.vidocq.vauban.core, as an intercepted subclass does. */
    static byte[] callingCore(String fqn) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> clb.withMethodBody("call",
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        ClassDesc.of("io.vidocq.vauban.core.interceptor.InterceptorManager")),
                ClassFile.ACC_PUBLIC, cob -> cob.return_()));
    }

    static Path jar(Path file, Map<String, byte[]> entries, Map<String, String> sectionDigests) throws Exception {
        return jar(file, entries, sectionDigests, false);
    }

    static Path jar(Path file, Map<String, byte[]> entries, Map<String, String> sectionDigests,
                    boolean multiRelease) throws Exception {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }
        sectionDigests.forEach((entry, digest) -> {
            Attributes section = new Attributes();
            section.putValue("SHA-256-Digest", digest);
            manifest.getEntries().put(entry, section);
        });
        try (var out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    static Path patch(Path dir, Map<String, byte[]> classes) throws Exception {
        for (var e : classes.entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        return dir;
    }

    static ModuleDescriptor descriptorOf(Path jar) {
        return ModuleFinder.of(jar).findAll().iterator().next().descriptor();
    }

    @Test
    void writeDeclaresTheProvidersAndCarriesTheParkedClasses() throws Exception {
        Path source = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Service.class", emptyClass("org.dep.Service"),
                "META-INF/LIB.SF", "Signature-Version: 1.0\n".getBytes(StandardCharsets.UTF_8)),
                Map.of("org/dep/Service.class", "abc="));
        Path patchDir = patch(tmp.resolve("patch"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents"),
                "org/dep/Service$$Intercepted.class", callingCore("org.dep.Service$$Intercepted")));
        Path out = tmp.resolve("target/vidocq-enriched/lib-1.0.jar");

        EnrichedJars.write(source, null, patchDir, List.of("org.dep._VaubanComponents"), "org.dep:lib:1.0", source,
                out);

        ModuleDescriptor md = descriptorOf(out);
        assertEquals("org.dep", md.name());
        assertTrue(md.requires().stream().map(ModuleDescriptor.Requires::name).collect(Collectors.toSet())
                .containsAll(Set.of("io.vidocq.vauban.api", "io.vidocq.vauban.core")));
        assertEquals(List.of("org.dep._VaubanComponents"), md.provides().stream()
                .filter(p -> p.service().equals(SPI)).findFirst().orElseThrow().providers());
        try (JarFile copy = new JarFile(out.toFile())) {
            assertTrue(copy.getEntry("org/dep/_VaubanComponents.class") != null);
            assertTrue(copy.getEntry("org/dep/Service$$Intercepted.class") != null);
            assertNull(copy.getEntry("META-INF/LIB.SF"), "the signature no longer matches: dropped");
            assertEquals("org.dep._VaubanComponents\n", new String(copy.getInputStream(
                    copy.getEntry("META-INF/services/" + SPI)).readAllBytes(), StandardCharsets.UTF_8));
            Attributes main = copy.getManifest().getMainAttributes();
            assertEquals("org.dep:lib:1.0", main.getValue("Vidocq-Enriched-From"));
            assertTrue(main.getValue("Vidocq-Enriched-Digest").startsWith("sha256:"));
            assertTrue(copy.getManifest().getEntries().isEmpty(), "per-entry digests are dropped");
        }
    }

    @Test
    void resolvePrefersTheEnrichedCopyThenTheModularizedOne() throws Exception {
        Path build = tmp.resolve("target");
        Path original = Files.createDirectories(tmp.resolve("m2")).resolve("lib.jar");
        Files.writeString(original, "x");
        assertEquals(original, EnrichedJars.resolve(build, original));

        Path modularized = build.resolve("vauban-modularized/lib.jar");
        Files.createDirectories(modularized.getParent());
        Files.writeString(modularized, "x");
        assertEquals(modularized, EnrichedJars.resolve(build, original));

        Path enriched = EnrichedJars.root(build).resolve("lib.jar");
        Files.createDirectories(enriched.getParent());
        Files.writeString(enriched, "x");
        assertEquals(enriched, EnrichedJars.resolve(build, original));
        assertTrue(EnrichedJars.isEnriched(build, original));
    }

    @Test
    void clearRemovesEveryEnrichedCopy() throws Exception {
        Path build = tmp.resolve("target");
        Path stale = EnrichedJars.root(build).resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "x");

        EnrichedJars.clear(build);

        assertFalse(Files.exists(stale));
    }

    @Test
    void enrichAllSynthesizesAnOpenModuleForAnAutomaticJar() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/auto-lib-1.0.jar"),
                Map.of("org/auto/A.class", emptyClass("org.auto.A")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "auto-lib"), Map.of(
                "org/auto/_VaubanComponents.class", emptyClass("org.auto._VaubanComponents")));
        List<String> warnings = new ArrayList<>();

        List<Path> enriched = EnrichedJars.enrichAll(build,
                List.of(new EnrichedJars.Scanned(auto, "auto-lib", "org.auto:auto-lib:1.0")),
                List.of("org.auto._VaubanComponents"), List.of(auto), line -> { }, warnings::add);

        assertEquals(List.of(auto), enriched);
        assertEquals(List.of(), warnings);
        ModuleDescriptor md = descriptorOf(EnrichedJars.resolve(build, auto));
        assertFalse(md.isAutomatic());
        assertTrue(md.isOpen());
        assertTrue(md.provides().stream().anyMatch(p -> p.service().equals(SPI)));
    }

    static Object fileKey(Path file) throws Exception {
        return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    }

    @Test
    void anUnchangedCopyIsNotRewrittenSinceARunningJvmMayHoldItOpen() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Service.class", emptyClass("org.dep.Service")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents")));
        var scanned = List.of(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0"));
        EnrichedJars.enrichAll(build, scanned, List.of("org.dep._VaubanComponents"), List.of(lib), line -> { },
                line -> { });
        Path copy = EnrichedJars.resolve(build, lib);
        FileTime old = FileTime.fromMillis(0);
        Files.setLastModifiedTime(copy, old);
        Object key = fileKey(copy);

        List<Path> again = EnrichedJars.enrichAll(build, scanned, List.of("org.dep._VaubanComponents"),
                List.of(lib), line -> { }, line -> { });

        assertEquals(List.of(lib), again);
        assertEquals(old, Files.getLastModifiedTime(copy), "an unchanged copy must not be written again");
        assertEquals(key, fileKey(copy), "nor replaced by a new file");
    }

    @Test
    void aChangedPatchRewritesTheCopyAndAJarNoLongerScannedLosesItsCopy() throws Exception {
        Path build = tmp.resolve("target");
        Path lib = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Service.class", emptyClass("org.dep.Service")), Map.of());
        Path other = jar(tmp.resolve("m2/other-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.other"),
                "org/other/O.class", emptyClass("org.other.O")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents")));
        patch(JpmsPatches.patchDirFor(build, "other"), Map.of(
                "org/other/_VaubanComponents.class", emptyClass("org.other._VaubanComponents")));
        List<String> providers = List.of("org.dep._VaubanComponents", "org.other._VaubanComponents");
        EnrichedJars.enrichAll(build, List.of(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0"),
                        new EnrichedJars.Scanned(other, "other", "org.other:other:1.0")), providers,
                List.of(lib, other), line -> { }, line -> { });
        assertTrue(EnrichedJars.isEnriched(build, other));

        patch(JpmsPatches.patchDirFor(build, "lib"), Map.of(
                "org/dep/Service_ClientProxy.class", emptyClass("org.dep.Service_ClientProxy")));
        EnrichedJars.enrichAll(build, List.of(new EnrichedJars.Scanned(lib, "lib", "org.dep:lib:1.0")), providers,
                List.of(lib, other), line -> { }, line -> { });

        try (JarFile copy = new JarFile(EnrichedJars.resolve(build, lib).toFile())) {
            assertTrue(copy.getEntry("org/dep/Service_ClientProxy.class") != null, "the new class is in the copy");
        }
        assertFalse(EnrichedJars.isEnriched(build, other), "a jar no longer scanned keeps no stale copy");
    }

    @Test
    void atPackagingTheCopyTakesTheDescriptorVaubanModularizeWrote() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/auto-lib-1.0.jar"),
                Map.of("org/auto/A.class", emptyClass("org.auto.A")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "auto-lib"), Map.of(
                "org/auto/_VaubanComponents.class", emptyClass("org.auto._VaubanComponents")));
        var dep = new EnrichedJars.Scanned(auto, "auto-lib", "org.auto:auto-lib:1.0");
        // A clean build: generate runs before vauban:modularize, so it synthesizes its own descriptor.
        EnrichedJars.enrichAll(build, List.of(dep), List.of("org.auto._VaubanComponents"), List.of(auto),
                line -> { }, line -> { });
        // prepare-package: vauban:modularize, configured with a module name, writes its copy.
        jar(build.resolve("vauban-modularized/auto-lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.renamed"),
                "org/auto/A.class", emptyClass("org.auto.A")), Map.of());

        EnrichedJars.rebaseOnModularized(build, dep, line -> { });

        Path copy = EnrichedJars.resolve(build, auto);
        ModuleDescriptor md = descriptorOf(copy);
        assertEquals("org.renamed", md.name());
        assertEquals(List.of("org.auto._VaubanComponents"), md.provides().stream()
                .filter(p -> p.service().equals(SPI)).findFirst().orElseThrow().providers());
        // The next build's generate starts from that same modularized jar: it keeps the copy as it is.
        FileTime old = FileTime.fromMillis(0);
        Files.setLastModifiedTime(copy, old);
        EnrichedJars.enrichAll(build, List.of(dep), List.of("org.auto._VaubanComponents"), List.of(auto),
                line -> { }, line -> { });
        assertEquals(old, Files.getLastModifiedTime(copy));
        assertEquals("org.renamed", descriptorOf(copy).name());
    }

    @Test
    void aJarWithOnlyAVersionedDescriptorIsLeftToPatching() throws Exception {
        Path build = tmp.resolve("target");
        Path mr = jar(tmp.resolve("m2/mr-lib-1.0.jar"), Map.of(
                "META-INF/versions/11/module-info.class", descriptor("org.mr"),
                "org/mr/A.class", emptyClass("org.mr.A")), Map.of(), true);
        patch(JpmsPatches.patchDirFor(build, "mr-lib"), Map.of(
                "org/mr/_VaubanComponents.class", emptyClass("org.mr._VaubanComponents")));
        List<String> warnings = new ArrayList<>();

        List<Path> enriched = EnrichedJars.enrichAll(build,
                List.of(new EnrichedJars.Scanned(mr, "mr-lib", "org.mr:mr-lib:1.0")),
                List.of("org.mr._VaubanComponents"), List.of(mr), line -> { }, warnings::add);

        assertEquals(List.of(), enriched);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("mr-lib"), warnings.getFirst());
    }
}
