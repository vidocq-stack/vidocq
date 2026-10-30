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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code vidocq:jlink} stages into its module directory before running jlink. */
class VidocqJlinkMojoStagingTest {

    private static final String VERSION = "1.0";
    private static final String SHARED_RESOURCE = "web/index.html";

    @TempDir
    Path dir;

    private Path target() {
        return dir.resolve("target");
    }

    private Path stage() {
        return target().resolve("jlink-mods");
    }

    @Test
    void eachDependencyIsStagedFromItsBestCopy() throws Exception {
        Path plain = moduleJar(dir.resolve("plain.jar"), "org.plain", Map.of());
        Path devOnly = moduleJar(dir.resolve("dev.jar"), "org.dev", Map.of(DevOnlyJars.MANIFEST_ENTRY, "true"));
        Path sealedOriginal = moduleJar(dir.resolve("sealed.jar"), "org.sealed", Map.of());
        Path sealedCopy = moduleJar(target().resolve("cassini-repackaged/sealed-" + VERSION + "-cassini.jar"),
                "org.sealed", Map.of("Sealed", "yes"));
        Path automatic = automaticJar(dir.resolve("auto.jar"), "org.auto");
        Path modularized = moduleJar(target().resolve("vauban-modularized/auto.jar"), "org.auto", Map.of());
        Path enrichedOriginal = automaticJar(dir.resolve("rich.jar"), "org.rich");
        Path enrichedCopy = moduleJar(target().resolve(EnrichedJars.DIR_NAME + "/rich.jar"), "org.rich", Map.of());
        Path patched = moduleJar(dir.resolve("patched.jar"), "org.patched", Map.of());
        Path parked = JpmsPatches.patchDirFor(target(), "patched").resolve("org/patched/Generated.class");
        Files.createDirectories(parked.getParent());
        Files.write(parked, ClassFile.of().build(ClassDesc.of("org.patched.Generated"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
        Artifact pom = artifact("parent", null);
        VidocqJlinkMojo mojo = mojo(false, artifact("plain", plain), artifact("dev", devOnly),
                artifact("sealed", sealedOriginal), artifact("auto", automatic), artifact("rich", enrichedOriginal),
                artifact("patched", patched), pom);

        mojo.stageModules();

        assertTrue(Files.isRegularFile(stage().resolve("app.jar")), "the application jar is staged");
        assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(stage().resolve("plain.jar")));
        assertFalse(Files.exists(stage().resolve("dev.jar")), "a dev-only jar is dropped");
        assertArrayEquals(Files.readAllBytes(sealedCopy), Files.readAllBytes(stage().resolve("sealed.jar")));
        assertArrayEquals(Files.readAllBytes(modularized), Files.readAllBytes(stage().resolve("auto.jar")));
        assertArrayEquals(Files.readAllBytes(enrichedCopy), Files.readAllBytes(stage().resolve("rich.jar")));
        try (JarFile staged = new JarFile(stage().resolve("patched.jar").toFile())) {
            assertNotNull(staged.getEntry("org/patched/Generated.class"), "the parked class rides in the jar");
        }
    }

    @Test
    void anAutomaticModuleFailsTheStagingWhenModularizeIsOff() throws Exception {
        VidocqJlinkMojo mojo = mojo(false, artifact("auto", automaticJar(dir.resolve("auto.jar"), "org.auto")));
        set(mojo, "modularize", false);

        IOException e = assertThrows(IOException.class, mojo::stageModules);

        assertTrue(e.getMessage().startsWith("jlink does not support automatic modules: org.auto"), e.getMessage());
    }

    /**
     * An automatic module, Flyway's or a JDBC driver's, gets its module descriptor before staging: the image no
     * longer needs a hand-run {@code vauban:modularize} (BUG-20261030-17, item 2).
     */
    @Test
    void anAutomaticModuleIsModularizedBeforeStaging() throws Exception {
        VidocqJlinkMojo mojo = mojo(false, artifact("auto", automaticJar(dir.resolve("auto.jar"), "org.auto")));

        mojo.stageModules();

        try (JarFile staged = new JarFile(stage().resolve("auto.jar").toFile())) {
            assertNotNull(staged.getEntry("module-info.class"), "the staged copy carries a module descriptor");
        }
        assertTrue(Files.isRegularFile(target().resolve("vauban-modularized/auto.jar")));
    }

    @Test
    void aSharedResourceDirectoryFailsWithoutTheMergeFlag() throws Exception {
        VidocqJlinkMojo mojo = mojo(false, sharedResourceArtifacts());

        IOException e = assertThrows(IOException.class, mojo::stageModules);

        assertTrue(e.getMessage().contains("both hold web/"), e.getMessage());
    }

    @Test
    void aSharedResourceDirectoryIsMergedWithTheMergeFlag() throws Exception {
        VidocqJlinkMojo mojo = mojo(true, sharedResourceArtifacts());

        mojo.stageModules();

        int holders = 0;
        for (String name : List.of("one.jar", "two.jar")) {
            try (JarFile staged = new JarFile(stage().resolve(name).toFile())) {
                holders += staged.getEntry(SHARED_RESOURCE) == null ? 0 : 1;
            }
        }
        assertEquals(1, holders, "one jar of the image holds the shared directory");
    }

    private Artifact[] sharedResourceArtifacts() throws Exception {
        Path one = moduleJar(dir.resolve("one.jar"), "org.one", Map.of());
        Path two = moduleJar(dir.resolve("two.jar"), "org.two", Map.of());
        addEntry(one, SHARED_RESOURCE);
        addEntry(two, SHARED_RESOURCE);
        return new Artifact[] {artifact("one", one), artifact("two", two)};
    }

    private VidocqJlinkMojo mojo(boolean mergeSharedResources, Artifact... artifacts) throws Exception {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion(VERSION);
        Build build = new Build();
        build.setDirectory(target().toString());
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        Artifact main = artifact("app", moduleJar(dir.resolve("app.jar"), "com.example.app", Map.of()));
        project.setArtifact(main);
        project.setArtifacts(new LinkedHashSet<>(List.of(artifacts)));
        VidocqJlinkMojo mojo = new VidocqJlinkMojo();
        set(mojo, "project", project);
        set(mojo, "buildDir", target().toFile());
        set(mojo, "modsDir", stage().toFile());
        set(mojo, "mergeSharedResources", mergeSharedResources);
        set(mojo, "modularize", true);
        return mojo;
    }

    private static Artifact artifact(String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact("org.dep", artifactId, VERSION, "runtime", file == null ? "pom" : "jar",
                "", new DefaultArtifactHandler(file == null ? "pom" : "jar"));
        if (file != null) {
            artifact.setFile(file.toFile());
        }
        return artifact;
    }

    private static void set(VidocqJlinkMojo mojo, String field, Object value) throws Exception {
        Field f = VidocqJlinkMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }

    /** A jar with an explicit descriptor for {@code module}, a class in its package and the given main attributes. */
    private static Path moduleJar(Path jar, String module, Map<String, String> attributes) throws IOException {
        Manifest manifest = manifest(attributes);
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("module-info.class"));
            out.write(ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), mb ->
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
            out.closeEntry();
            marker(out, module);
        }
        return jar;
    }

    /** A jar without a descriptor, named {@code module} by its manifest. */
    private static Path automaticJar(Path jar, String module) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(jar),
                manifest(Map.of("Automatic-Module-Name", module)))) {
            marker(out, module);
        }
        return jar;
    }

    private static Manifest manifest(Map<String, String> attributes) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach(manifest.getMainAttributes()::putValue);
        return manifest;
    }

    private static void marker(JarOutputStream out, String module) throws IOException {
        out.putNextEntry(new ZipEntry(module.replace('.', '/') + "/Marker.class"));
        out.write(ClassFile.of().build(ClassDesc.of(module + ".Marker"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
        out.closeEntry();
    }

    /** Rewrites {@code jar} with a text entry {@code name} added. */
    private static void addEntry(Path jar, String name) throws IOException {
        Path copy = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (JarFile in = new JarFile(jar.toFile());
             var out = new JarOutputStream(Files.newOutputStream(copy), in.getManifest())) {
            for (var entry : java.util.Collections.list(in.entries())) {
                if (!entry.getName().equals(JarFile.MANIFEST_NAME)) {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    try (var is = in.getInputStream(entry)) {
                        is.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
            out.putNextEntry(new ZipEntry(name));
            out.write("<html/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.move(copy, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
