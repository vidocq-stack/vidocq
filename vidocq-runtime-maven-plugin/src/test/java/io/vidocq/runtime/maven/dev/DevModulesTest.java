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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.DevOnlyJars;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevModules#companionOf} reads a jar's {@code META-INF/vidocq/dev-module} descriptor, and {@link
 * DevModules#collect} resolves and adds each project artifact's companion, skipping what is already on the path and
 * never bringing in runtime code the application lacks (Vidocq/vidocq#143).
 */
class DevModulesTest {

    @Test
    void theDescriptorNamesOneCompanion(@TempDir Path dir) throws Exception {
        Path a = withDescriptor(dir, "a.jar", "# comment\n\nvidocq-runtime-knock-health-extension-dev\n");
        assertEquals(Optional.of("vidocq-runtime-knock-health-extension-dev"), DevModules.companionOf(a, w -> {}));
        assertEquals(Optional.empty(), DevModules.companionOf(plainJar(dir, "b.jar"), w -> {}));
        List<String> warnings = new ArrayList<>();
        Path c = withDescriptor(dir, "c.jar", "one\ntwo\n");
        assertEquals(Optional.empty(), DevModules.companionOf(c, warnings::add));
        Path d = withDescriptor(dir, "d.jar", "bad id!");
        assertEquals(Optional.empty(), DevModules.companionOf(d, warnings::add));
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void aCompanionAndItsDevOnlyDependenciesAreAddedOnce(@TempDir Path dir) throws Exception {
        String group = "io.vidocq.runtime.extensions.microprofile";
        String knockAid = "vidocq-runtime-knock-health-extension";
        Artifact knock = artifact(group, knockAid, withDescriptor(dir, "knock.jar", knockAid + "-dev"));
        Path dev = markedJar(dir, "knock-dev.jar");
        Path spi = markedJar(dir, "spi.jar");
        DevModules.Resolver resolver = coords -> List.of(
                new DevModules.Resolved(group + ":" + knockAid + "-dev", dev),
                new DevModules.Resolved(group + ":" + knockAid, knock.getFile().toPath()),
                new DevModules.Resolved("io.vidocq.runtime:vidocq-runtime-devconsole-spi", spi));

        List<Path> added = DevModules.collect(List.of(knock), resolver, w -> {}, i -> {});

        assertEquals(List.of(dev, spi), added, "the extension itself is already on the path");
    }

    @Test
    void aCompanionNeedingRuntimeCodeTheApplicationLacksIsSkipped(@TempDir Path dir) throws Exception {
        Artifact knock = artifact("g", "ext", withDescriptor(dir, "ext.jar", "ext-dev"));
        Path dev = markedJar(dir, "ext-dev.jar");
        Path runtimeLib = plainJar(dir, "lib.jar");
        List<String> warnings = new ArrayList<>();

        List<Path> added = DevModules.collect(List.of(knock), coords -> List.of(
                new DevModules.Resolved("g:ext-dev", dev), new DevModules.Resolved("g:lib", runtimeLib)),
                warnings::add, i -> {});

        assertEquals(List.of(), added);
        assertTrue(warnings.getFirst().contains("g:lib"), warnings.toString());
    }

    @Test
    void anUnresolvableCompanionWarnsAndNeverFails(@TempDir Path dir) throws Exception {
        Artifact knock = artifact("g", "ext", withDescriptor(dir, "ext.jar", "ext-dev"));
        List<String> warnings = new ArrayList<>();

        List<Path> added = DevModules.collect(List.of(knock), coords -> {
            throw new IllegalStateException("offline");
        }, warnings::add, i -> {});

        assertEquals(List.of(), added);
        assertTrue(warnings.getFirst().contains("g:ext-dev:1.0"), warnings.toString());
    }

    /**
     * The console step already put the SPI on the module path (Vidocq/vidocq#143, spec §5.3 "never twice"): even
     * though the companion's own transitive dependencies include it, it must not be added a second time.
     */
    @Test
    void aJarTheConsoleStepAlreadyAddedIsNotAddedAgainByCompanions(@TempDir Path dir) throws Exception {
        Artifact knock = artifact("g", "ext", withDescriptor(dir, "ext.jar", "ext-dev"));
        Path dev = markedJar(dir, "ext-dev.jar");
        Path spi = markedJar(dir, "spi.jar");
        Set<String> alreadyOnPath = new HashSet<>(Set.of("io.vidocq.runtime:vidocq-runtime-devconsole-spi"));

        List<Path> added = DevModules.collect(List.of(knock), coords -> List.of(
                new DevModules.Resolved("g:ext-dev", dev),
                new DevModules.Resolved("io.vidocq.runtime:vidocq-runtime-devconsole-spi", spi)),
                w -> {}, i -> {}, alreadyOnPath);

        assertEquals(List.of(dev), added, "the SPI jar the console step already added must not be added twice");
    }

    /** A jar carrying the {@code META-INF/vidocq/dev-module} descriptor, no manifest attribute of its own. */
    static Path withDescriptor(Path dir, String name, String content) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry(DevModules.DESCRIPTOR));
            jarOut.write(content.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    /** A jar with the {@code Vidocq-Dev-Only: true} manifest entry — a dev-only runtime dependency. */
    static Path markedJar(Path dir, String name) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, "true");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    /** A plain jar: no descriptor, no dev-only manifest entry — ordinary runtime code. */
    static Path plainJar(Path dir, String name) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    static Artifact artifact(String groupId, String artifactId, Path jar) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "1.0", "runtime", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(jar.toFile());
        return artifact;
    }
}
