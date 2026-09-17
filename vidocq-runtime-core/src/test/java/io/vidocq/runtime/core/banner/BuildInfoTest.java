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
package io.vidocq.runtime.core.banner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static io.vidocq.runtime.core.banner.BannerTestSupport.directory;
import static io.vidocq.runtime.core.banner.BannerTestSupport.identity;
import static io.vidocq.runtime.core.banner.BannerTestSupport.jar;
import static io.vidocq.runtime.core.banner.BannerTestSupport.utf8;
import static io.vidocq.runtime.core.banner.BannerTestSupport.writeJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reading a build inside its own archive, and the identity line it gives. */
class BuildInfoTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ identity line, golden cases

    @Test
    void identityLineGoldenCases() {
        assertEquals("Vidocq 0.3.0",
                identityLine(jar("0.3.0", "e1c8685e", false, null, "2026-08-30T20:07:01Z", "2026-08-30T20:07:01Z")));
        assertEquals("Vidocq 0.4.0-SNAPSHOT (9beafc47, built 2026-09-17T14:02:11Z)",
                identityLine(jar("0.4.0-SNAPSHOT", "9beafc47", false, "2026-09-17T14:02:11Z", "2026-09-15T14:08:34Z", null)));
        assertEquals("Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)",
                identityLine(jar("0.4.0-SNAPSHOT", "9beafc47", true, "2026-09-17T14:02:11Z", null, null)));
        assertEquals("Vidocq 0.3.0 (e1c8685e+dirty, built 2026-09-17T14:02:11Z)",
                identityLine(jar("0.3.0", "e1c8685e", true, "2026-09-17T14:02:11Z", null, null)));
        assertEquals("Vidocq 0.4.0-SNAPSHOT (9beafc47, committed 2026-08-30T20:07:01Z)",
                identityLine(jar("0.4.0-SNAPSHOT", "9beafc47", false, null, "2026-08-30T20:07:01Z", "2026-09-15T15:53:21Z")));
        assertEquals("Vidocq 0.4.0-SNAPSHOT (jar dated 2026-09-15T15:53:21Z)",
                identityLine(jar("0.4.0-SNAPSHOT", null, null, null, null, "2026-09-15T15:53:21Z")));
        assertEquals("Vidocq version unknown (classes in vidocq-runtime-core/target/classes)",
                identityLine(directory(null, "/home/dev/vidocq/vidocq-runtime-core/target/classes")));
        assertEquals("Vidocq 0.4.0-SNAPSHOT? (last Maven build, classes in vauban-api/target/classes)",
                identityLine(directory("0.4.0-SNAPSHOT", "/home/dev/vauban/vauban-api/target/classes")));
    }

    @Test
    void aLongDirectoryDropsTheLastMavenBuildLabelBeforeBeingCut() {
        StartupIdentity id = identity(directory("0.4.0-SNAPSHOT", "/home/dev/vidocq/vidocq-runtime-core/target/classes"),
                null, null, null, null);

        assertEquals("Vidocq 0.4.0-SNAPSHOT? (last Maven build, classes in vidocq-runtime-core/target/classes)",
                id.identityLine());
        assertEquals("Vidocq 0.4.0-SNAPSHOT? (classes in vidocq-runtime-core/target/classes)", id.identityLine(79));
    }

    /** Every combination of absent fields: never {@code null}, never wider than 80 columns. */
    @Test
    void identityLinesAreNeverNullNorWiderThan80Columns() {
        Path deep = Path.of("/home/a-developer-with-a-long-name/projects/perso/vidocq/some-very-long-module-name-for-tests/target/classes");
        for (int mask = 0; mask < 1 << 8; mask++) {
            boolean exploded = (mask & 64) != 0;
            BuildInfo info = new BuildInfo(
                    (mask & 1) != 0 ? "io.vidocq.runtime.core" : null,
                    (mask & 2) != 0 ? "0.4.0-SNAPSHOT" : null,
                    (mask & 4) != 0 ? "9beafc47" : null,
                    (mask & 8) != 0 ? Boolean.TRUE : null,
                    (mask & 16) != 0 ? Instant.parse("2026-09-17T14:02:11Z") : null,
                    (mask & 32) != 0 ? Instant.parse("2026-08-30T20:07:01Z") : null,
                    (mask & 128) != 0 ? (exploded ? deep.toUri() : Path.of("/opt/core.jar").toUri()) : null,
                    exploded,
                    (mask & 128) != 0 ? Instant.parse("2026-09-15T15:53:21Z") : null,
                    null);
            String lines = StartupBanner.identityLines(
                    identity(info, "Eclipse Adoptium", "profile dev", "io.vidocq.tools.lc4jcdi.mcptimeserver", "0.1.0-SNAPSHOT"),
                    false);
            assertFalse(lines.contains("null"), lines);
            for (String line : lines.lines().toList()) {
                assertTrue(line.length() <= 80, line.length() + " columns: " + line);
            }
            assertTrue(lines.startsWith(" Vidocq "), lines);
        }
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void unfilteredUnknownEmptyAndInvalidValuesAreAbsent() throws Exception {
        Path archive = writeJar(dir.resolve("brick.jar"), Map.of(
                "META-INF/vidocq/build-info/brick.properties", utf8("""
                        git.build.version=${project.version}
                        git.commit.id.abbrev=${git.commit.id.abbrev}
                        git.dirty=unknown
                        git.build.time=
                        git.commit.time=yesterday
                        """)));

        BuildInfo info = BuildInfo.read(null, null, archive.toUri());

        assertNull(info.version());
        assertNull(info.commit());
        assertNull(info.dirty());
        assertNull(info.builtAt());
        assertNull(info.committedAt());
        assertTrue(info.fileDatedAt() != null, "the jar date stands in");
        assertTrue(info.describe().startsWith("version unknown (jar dated "), info.describe());
    }

    @Test
    void escapedColonsAndTheDirtyFlagAreRead() throws Exception {
        Path archive = writeJar(dir.resolve("vidocq-runtime-core.jar"), Map.of(
                "META-INF/vidocq/build-info/vidocq-runtime-core.properties", utf8("""
                        #Generated by Git-Commit-Id-Plugin
                        git.build.time=2026-09-17T14\\:02\\:11Z
                        git.build.version=0.4.0-SNAPSHOT
                        git.commit.id.abbrev=9beafc47
                        git.commit.time=2026-09-15T16\\:08\\:34+02\\:00
                        git.dirty=true
                        """)));

        BuildInfo info = BuildInfo.read(null, null, archive.toUri());

        assertEquals("0.4.0-SNAPSHOT", info.version());
        assertEquals(Instant.parse("2026-09-17T14:02:11Z"), info.builtAt());
        assertEquals(Instant.parse("2026-09-15T14:08:34Z"), info.committedAt(), "converted to UTC");
        assertTrue(info.isDirty());
        assertEquals("0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)", info.describe());
    }

    @Test
    void theVersionComesFromTheDescriptorThenTheBuildInfoThenPomProperties() throws Exception {
        Path both = writeJar(dir.resolve("both.jar"), Map.of(
                "META-INF/vidocq/build-info/brick.properties", utf8("git.build.version=2.0.0\n"),
                "META-INF/maven/io.vidocq.brick/brick/pom.properties", utf8("artifactId=brick\nversion=3.0.0\n")));
        Path pomOnly = writeJar(dir.resolve("pom-only.jar"), Map.of(
                "META-INF/maven/io.vidocq.brick/brick/pom.properties", utf8("artifactId=brick\nversion=3.0.0\n")));
        Files.setLastModifiedTime(pomOnly, FileTime.from(Instant.parse("2026-09-15T15:53:21.750Z")));

        assertEquals("1.0.0", BuildInfo.read("brick", "1.0.0", both.toUri()).version());
        assertEquals("2.0.0", BuildInfo.read("brick", null, both.toUri()).version());
        assertEquals("brick", BuildInfo.read("brick", null, both.toUri()).artifactId());
        BuildInfo pom = BuildInfo.read(null, null, pomOnly.toUri());
        assertEquals("3.0.0", pom.version());
        assertEquals(Instant.parse("2026-09-15T15:53:21Z"), pom.fileDatedAt());
        assertEquals("3.0.0", pom.describe(), "a release without build identity shows its version alone");
    }

    @Test
    void aShadedArchivePicksTheFileOfItsOwnArtifact() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/maven/io.vidocq.brick/brick/pom.properties", utf8("artifactId=brick\nversion=1.0.0\n"));
        entries.put("META-INF/vidocq/build-info/brick.properties", utf8("git.commit.id.abbrev=aaaaaaaa\n"));
        entries.put("META-INF/vidocq/build-info/shaded-dependency.properties", utf8("git.commit.id.abbrev=bbbbbbbb\n"));
        Path archive = writeJar(dir.resolve("brick-shaded.jar"), entries);

        assertEquals("aaaaaaaa", BuildInfo.read(null, null, archive.toUri()).commit());
    }

    // ------------------------------------------------------------------ class path

    /** On the class path every archive shares one unnamed module: each brick must read its own file. */
    @Test
    void onTheClassPathEachBrickReadsItsOwnArchive() throws Exception {
        Path a = brickJar("brick-a", "brick.a.Anchor", "aaaaaaaa");
        Path b = brickJar("brick-b", "brick.b.Anchor", "bbbbbbbb");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {a.toUri().toURL(), b.toUri().toURL()}, null)) {
            BuildInfo first = BuildInfo.ofClass(Class.forName("brick.a.Anchor", false, loader));
            BuildInfo second = BuildInfo.ofClass(Class.forName("brick.b.Anchor", false, loader));

            assertEquals("aaaaaaaa", first.commit());
            assertEquals("brick-a", first.artifactId());
            assertEquals("bbbbbbbb", second.commit());
            assertEquals("brick-b", second.artifactId());
            assertNull(second.module());
            assertEquals("1.0.0-SNAPSHOT (bbbbbbbb, built 2026-09-17T14:02:11Z)", second.describe());
        }
    }

    // ------------------------------------------------------------------ exploded directories

    @Test
    void anExplodedDirectoryShowsWhereTheClassesComeFrom() throws Exception {
        Path versioned = moduleDirectory("vauban-api/target/classes", "brick.versioned", "0.4.0-SNAPSHOT");
        Files.createDirectories(versioned.resolve("META-INF/vidocq/build-info"));
        Files.writeString(versioned.resolve("META-INF/vidocq/build-info/vauban-api.properties"),
                "git.commit.id.abbrev=0ldc0mm1\ngit.build.version=0.4.0-SNAPSHOT\n");
        Path unversioned = moduleDirectory("vidocq-runtime-core/target/classes", "brick.unversioned", null);

        assertEquals("0.4.0-SNAPSHOT? (last Maven build, classes in vauban-api/target/classes)",
                BuildInfo.of(module(versioned, "brick.versioned")).describe());
        assertEquals("version unknown (classes in vidocq-runtime-core/target/classes)",
                BuildInfo.of(module(unversioned, "brick.unversioned")).describe());
    }

    @Test
    void aNamedModuleInAJarReadsItsDescriptorVersionAndItsFile() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("module-info.class", moduleInfo("brick.jarred", "0.4.0-SNAPSHOT"));
        entries.put("META-INF/vidocq/build-info/brick-jarred.properties",
                utf8("git.commit.id.abbrev=12345678\ngit.dirty=false\ngit.build.time=2026-09-17T14\\:02\\:11Z\n"));
        entries.put("META-INF/maven/io.vidocq.brick/brick-jarred/pom.properties",
                utf8("artifactId=brick-jarred\nversion=0.4.0-SNAPSHOT\n"));
        Path jar = writeJar(dir.resolve("brick-jarred.jar"), entries);

        BuildInfo info = BuildInfo.of(module(jar, "brick.jarred"));

        assertEquals("brick.jarred", info.module());
        assertEquals("brick-jarred", info.artifactId());
        assertEquals("0.4.0-SNAPSHOT (12345678, built 2026-09-17T14:02:11Z)", info.describe());
    }

    // ------------------------------------------------------------------ this module's build identity

    /**
     * The file {@code vidocq-parent} generates in {@code target/classes}. Skipped when the parent in use
     * predates it (or with {@code -Dmaven.gitcommitid.skip=true}).
     */
    @Test
    void theBuildIdentityOfThisModuleIsFiltered() throws Exception {
        Properties properties = new Properties();
        Module core = StartupBanner.class.getModule();
        try (InputStream in = core.isNamed()
                ? core.getResourceAsStream(BuildInfo.resource("vidocq-runtime-core"))
                : StartupBanner.class.getClassLoader().getResourceAsStream(BuildInfo.resource("vidocq-runtime-core"))) {
            assumeTrue(in != null, "no build identity file: vidocq-parent without git-commit-id-maven-plugin");
            properties.load(in);
        }

        assertTrue(properties.containsKey("git.build.version"), properties.toString());
        for (String key : properties.stringPropertyNames()) {
            assertFalse(properties.getProperty(key).startsWith("${"), key + "=" + properties.getProperty(key));
        }
        if (core.isNamed() && core.getDescriptor().rawVersion().isPresent()) {
            assertEquals(core.getDescriptor().rawVersion().get(), properties.getProperty("git.build.version"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String identityLine(BuildInfo info) {
        return identity(info, null, null, null, null).identityLine();
    }

    private Path brickJar(String artifactId, String anchor, String commit) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(anchor.replace('.', '/') + ".class", ClassFile.of().build(ClassDesc.of(anchor),
                cb -> cb.withFlags(AccessFlag.PUBLIC).withSuperclass(ConstantDescs.CD_Object)));
        entries.put("META-INF/maven/io.vidocq.test/" + artifactId + "/pom.properties",
                utf8("artifactId=" + artifactId + "\nversion=1.0.0-SNAPSHOT\n"));
        entries.put(BuildInfo.resource(artifactId), utf8("git.build.version=1.0.0-SNAPSHOT\ngit.commit.id.abbrev="
                + commit + "\ngit.dirty=false\ngit.build.time=2026-09-17T14\\:02\\:11Z\n"));
        return writeJar(dir.resolve(artifactId + ".jar"), entries);
    }

    private Path moduleDirectory(String relative, String name, String version) throws Exception {
        Path classes = Files.createDirectories(dir.resolve(relative));
        Files.write(classes.resolve("module-info.class"), moduleInfo(name, version));
        return classes;
    }

    private static byte[] moduleInfo(String name, String version) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(name), module -> {
            module.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
            if (version != null) {
                module.moduleVersion(version);
            }
        }));
    }

    /** {@code name}, resolved from {@code path} into a layer of its own. */
    private static Module module(Path path, String name) {
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(path), ModuleFinder.of(), List.of(name));
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        return layer.findModule(name).orElseThrow();
    }
}
