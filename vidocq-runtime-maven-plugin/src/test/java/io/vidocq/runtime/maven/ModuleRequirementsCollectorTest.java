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

import io.vidocq.runtime.codegen.commons.Requirement;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage of {@link ModuleRequirementsCollector#applicableTo} — an opens applies only to a module that has the package. */
class ModuleRequirementsCollectorTest {

    @Test
    void opensAppliesOnlyWhenTheModuleContainsThePackage(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("db").resolve("migration"));
        Set<Requirement> required = new LinkedHashSet<>(List.of(
                Requirement.opens("db.migration", "Flyway"),
                Requirement.opens("absent.pkg", "x")));

        Set<Requirement> applicable = ModuleRequirementsCollector.applicableTo(required, out.toFile());

        assertTrue(applicable.contains(Requirement.opens("db.migration", "Flyway")),
                "an opens applies when the module has the package");
        assertFalse(applicable.contains(Requirement.opens("absent.pkg", "x")),
                "an opens for a package the module lacks must be dropped (e.g. an extension that "
                        + "depends on the migration extension but ships no db.migration)");
    }

    @Test
    void requiresAlwaysApplies(@TempDir Path out) {
        Set<Requirement> applicable = ModuleRequirementsCollector.applicableTo(
                Set.of(Requirement.requires("java.sql", "x")), out.toFile());
        assertTrue(applicable.contains(Requirement.requires("java.sql", "x")));
    }

    @Test
    void nullOutputDirectoryDropsOpens() {
        assertTrue(ModuleRequirementsCollector.applicableTo(
                Set.of(Requirement.opens("db.migration", "x")), null).isEmpty());
    }

    /**
     * An extension that ships no {@link io.vidocq.runtime.codegen.commons.ModuleRequirementsDescriptor#RESOURCE}
     * descriptor -- which is all but two of them -- still states its {@code requires} in the extension catalog,
     * and the check has to see them: Cassini does not re-export Jakarta REST, so an application that uses it
     * has to require {@code jakarta.ws.rs} itself.
     */
    @Test
    void catalogRequiresAreCollectedForAnExtensionWithoutADescriptor(@TempDir Path empty) {
        Artifact cassini = artifact("io.vidocq.runtime.extensions.jakartaee.core",
                "vidocq-runtime-cassini-rest-extension", empty);

        Set<Requirement> collected = ModuleRequirementsCollector.collect(Set.of(cassini), new SystemStreamLog());

        assertEquals(
                List.of("io.vidocq.cassini.api", "jakarta.cdi", "jakarta.inject", "jakarta.json.bind",
                        "jakarta.ws.rs"),
                collected.stream().filter(r -> r.kind() == Requirement.Kind.REQUIRES)
                        .map(Requirement::name).sorted().toList(),
                "the catalog's module-info.requires of cassini-rest must be collected");
        assertTrue(collected.stream().allMatch(r -> !r.reason().isBlank()),
                "every requirement carries a reason, so the goals can explain the fix");
    }

    @Test
    void anArtifactOutsideTheCatalogContributesNothing(@TempDir Path empty) {
        Artifact plain = artifact("org.example", "not-an-extension", empty);
        assertTrue(ModuleRequirementsCollector.collect(Set.of(plain), new SystemStreamLog()).isEmpty());
    }

    /** {@code requires static} stays out: it is a separate catalog key that {@link Requirement} cannot express. */
    @Test
    void catalogRequiresStaticIsNotReportedAsAPlainRequires(@TempDir Path empty) {
        Artifact mansartData = artifact("io.vidocq.runtime.extensions.jakartaee.web",
                "vidocq-runtime-mansart-data-extension", empty);

        Set<Requirement> collected = ModuleRequirementsCollector.collect(Set.of(mansartData), new SystemStreamLog());

        assertTrue(collected.stream()
                        .noneMatch(r -> "java.compiler".equals(r.name())
                                && r.kind() == Requirement.Kind.REQUIRES),
                "mansart-data declares java.compiler as requires static, which must not become a plain requires");
        assertTrue(collected.stream()
                        .anyMatch(r -> "java.compiler".equals(r.name())
                                && r.kind() == Requirement.Kind.REQUIRES_STATIC),
                "it must be collected as a static requirement instead");
    }

    @Test
    void aCatalogRequiresIsDroppedForAModuleThatUsesNoneOfTheExtensionsApi(@TempDir Path empty) {
        Set<Requirement> required = Set.of(
                Requirement.requires("jakarta.ws.rs", "cassini-rest does not re-export it"),
                Requirement.requiresStatic("java.compiler", "generated sources need it"));

        // Vidocq's own cassini-rest dev panel: it depends on the extension to extend it and imports
        // nothing from Jakarta REST, so the check must demand nothing (BUG-20261109-01).
        Set<Requirement> applicable =
                ModuleRequirementsCollector.applicableTo(required, empty.toFile(), Optional.of(Set.of()));

        assertTrue(applicable.isEmpty(), "a module that uses none of the API needs no requires: " + applicable);
    }

    @Test
    void aCatalogRequiresIsKeptForAModuleThatUsesTheApi(@TempDir Path empty) {
        Set<Requirement> required = Set.of(
                Requirement.requires("jakarta.ws.rs", "cassini-rest does not re-export it"),
                Requirement.requires("jakarta.json.bind", "cassini-rest does not re-export it"));

        Set<Requirement> applicable = ModuleRequirementsCollector.applicableTo(
                required, empty.toFile(), Optional.of(Set.of("jakarta.ws.rs")));

        assertEquals(1, applicable.size(), "only the module actually used stays: " + applicable);
        assertEquals("requires jakarta.ws.rs;", applicable.iterator().next().directive());
    }

    @Test
    void withoutUsageInformationNoRequiresIsFilteredOut(@TempDir Path empty) {
        Set<Requirement> required = Set.of(Requirement.requires("jakarta.ws.rs", "x"));

        // null means "could not tell" -- the check must stay conservative rather than silently pass.
        assertEquals(1, ModuleRequirementsCollector.applicableTo(required, empty.toFile(), Optional.empty()).size());
    }

    @Test
    void aModuleWithoutCompiledMainCodeNeedsNothing(@TempDir Path noClasses) {
        // A test-only integration module: target/classes exists but holds no class file, so nothing
        // in it can use an extension's API and no directive may be demanded (BUG-20261109-01).
        assertEquals(Optional.of(Set.of()),
                ModuleRequirementsCollector.modulesInUse(noClasses.toFile(), Set.of(), new SystemStreamLog()));
    }

    @Test
    void aMissingOutputDirectoryNeedsNothing() {
        assertEquals(Optional.of(Set.of()), ModuleRequirementsCollector.modulesInUse(
                new java.io.File("does-not-exist"), Set.of(), new SystemStreamLog()));
    }

    // --- modulesInUse: the usage analysis behind the requires filter ---

    @Test
    void aReferencedSystemModuleIsInUse(@TempDir Path classes) throws Exception {
        writeClassReferencing(classes, "io.app", "Uses", ClassDesc.of("java.sql", "Date"));

        Set<String> inUse = ModuleRequirementsCollector.modulesInUse(classes.toFile(), Set.of(), new SystemStreamLog())
                .orElseThrow();

        assertTrue(inUse.contains("java.sql"), "java.sql exports java.sql, which the class names: " + inUse);
        assertFalse(inUse.contains("java.compiler"), "nothing here touches javax.annotation.processing: " + inUse);
    }

    @Test
    void javaCompilerIsInUseOnlyForAModuleTouchingAnnotationProcessing(@TempDir Path classes) throws Exception {
        writeClassReferencing(classes, "io.app", "Generated",
                ClassDesc.of("javax.annotation.processing", "Generated"));

        assertTrue(ModuleRequirementsCollector
                        .modulesInUse(classes.toFile(), Set.of(), new SystemStreamLog())
                        .orElseThrow()
                        .contains("java.compiler"),
                "this is what keeps `requires static java.compiler` honest");
    }

    @Test
    void anAutomaticModuleOnThePathCountsAllItsPackages(@TempDir Path dir) throws Exception {
        // A jar without module-info is an automatic module: it offers every package it holds.
        Path jar = jarContaining(dir.resolve("vendor.lib-1.0.jar"), "com.vendor.api", "Client");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        writeClassReferencing(classes, "io.app", "Consumer", ClassDesc.of("com.vendor.api", "Client"));

        Set<String> inUse = ModuleRequirementsCollector.modulesInUse(
                classes.toFile(), Set.of(artifact("com.vendor", "lib", jar)), new SystemStreamLog()).orElseThrow();

        assertTrue(inUse.contains("vendor.lib"), "automatic module named from the jar: " + inUse);
    }

    @Test
    void anUnreadableModulePathLeavesEveryRequirementInPlace(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        writeClassReferencing(classes, "io.app", "Uses", ClassDesc.of("java.sql", "Date"));
        Path broken = Files.write(dir.resolve("broken.jar"), new byte[] {1, 2, 3});

        Optional<Set<String>> inUse = ModuleRequirementsCollector.modulesInUse(
                classes.toFile(), Set.of(artifact("com.vendor", "broken", broken)), new SystemStreamLog());

        // An empty Optional means "could not tell", and applicableTo then filters no requires away.
        assertEquals(1, ModuleRequirementsCollector.applicableTo(
                        Set.of(Requirement.requires("jakarta.ws.rs", "x")), classes.toFile(), inUse)
                .size(),
                "a module path ModuleFinder rejects must not silently drop requirements");
    }

    /** A class whose only interesting feature is a field of {@code referenced}. */
    private static void writeClassReferencing(Path classes, String pkg, String name, ClassDesc referenced)
            throws Exception {
        ClassDesc type = ClassDesc.of(pkg, name);
        byte[] bytes = ClassFile.of().build(type, cb -> cb.withField("field", referenced, ClassFile.ACC_PRIVATE));
        Path file = classes.resolve(pkg.replace('.', '/')).resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** A jar with one class and no module-info, so ModuleFinder sees an automatic module. */
    private static Path jarContaining(Path jar, String pkg, String name) throws Exception {
        ClassDesc type = ClassDesc.of(pkg, name);
        byte[] bytes = ClassFile.of().build(type, cb -> {});
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry(pkg.replace('.', '/') + "/" + name + ".class"));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jar;
    }

    private static Artifact artifact(String groupId, String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "0.4.0", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }
}
