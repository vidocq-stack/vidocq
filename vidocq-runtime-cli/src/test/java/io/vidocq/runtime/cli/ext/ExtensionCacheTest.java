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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionCacheTest {

    private static final ExtensionCache.Fingerprint FP =
            new ExtensionCache.Fingerprint(Path.of("/m2/a-1.0-SNAPSHOT.jar"), 10, 1000);

    private static final ProjectExtensions.Installed CASSINI = new ProjectExtensions.Installed(
            "cassini-rest",
            new ResolvedArtifact("io.vidocq.runtime.extensions.jakartaee.core",
                    "vidocq-runtime-cassini-rest-extension", "0.4.0",
                    Path.of("/m2/cassini-0.4.0.jar")),
            true);

    @TempDir
    Path dir;

    @Test
    void entrySurvivesARoundTrip() {
        var entry = new ExtensionCache.Entry("abc", List.of(FP), List.of(CASSINI));

        assertEquals(Optional.of(entry), ExtensionCache.read(ExtensionCache.write(entry)));
    }

    @Test
    void unreadableTextIsAMiss() {
        assertTrue(ExtensionCache.read("garbage\n").isEmpty());
        assertTrue(ExtensionCache.read("").isEmpty());
    }

    @Test
    void releaseOnlyEntryIsDefinitive() {
        var entry = new ExtensionCache.Entry("abc", List.of(), List.of(CASSINI));

        assertTrue(ExtensionCache.isValid(entry, "abc", file -> null));
    }

    @Test
    void changedPomInvalidates() {
        var entry = new ExtensionCache.Entry("abc", List.of(), List.of(CASSINI));

        assertFalse(ExtensionCache.isValid(entry, "def", file -> null));
    }

    @Test
    void snapshotIsValidOnlyWhileItsLocalFileIsUnchanged() {
        var entry = new ExtensionCache.Entry("abc", List.of(FP), List.of(CASSINI));

        assertTrue(ExtensionCache.isValid(entry, "abc", file -> FP));
        assertFalse(ExtensionCache.isValid(entry, "abc",
                file -> new ExtensionCache.Fingerprint(file, 10, 2000)));
        assertFalse(ExtensionCache.isValid(entry, "abc",
                file -> new ExtensionCache.Fingerprint(file, 11, 1000)));
        assertFalse(ExtensionCache.isValid(entry, "abc", file -> null));
    }

    @Test
    void fingerprintsEverySnapshotJarAndPomButNoRelease() throws IOException {
        Path repo = dir.resolve("repository");
        Path snapJar = artifact(repo, "com.acme", "lib", "1.0-SNAPSHOT");
        Path relJar = artifact(repo, "com.acme", "rel", "1.0");

        List<Path> files = ExtensionCache.snapshotFiles(List.of(
                new ResolvedArtifact("com.acme", "lib", "1.0-SNAPSHOT", snapJar),
                new ResolvedArtifact("com.acme", "rel", "1.0", relJar)), Optional.empty());

        assertEquals(List.of(snapJar, snapJar.resolveSibling("lib-1.0-SNAPSHOT.pom")), files);
    }

    @Test
    void fingerprintsASnapshotParentPomFoundInTheLocalRepository() throws IOException {
        Path repo = dir.resolve("repository");
        Path jar = artifact(repo, "io.vidocq.runtime", "vidocq-runtime-core", "0.4.0");
        var parent = new PomDependencies.Parent("io.vidocq.runtime", "vidocq-runtime-parent", "0.4.0-SNAPSHOT");

        List<Path> files = ExtensionCache.snapshotFiles(List.of(
                new ResolvedArtifact("io.vidocq.runtime", "vidocq-runtime-core", "0.4.0", jar)),
                Optional.of(parent));

        assertEquals(List.of(repo.resolve(
                "io/vidocq/runtime/vidocq-runtime-parent/0.4.0-SNAPSHOT/vidocq-runtime-parent-0.4.0-SNAPSHOT.pom")),
                files);
    }

    @Test
    void timestampedSnapshotIsFingerprintedAndLocatesTheRepository() throws IOException {
        Path repo = dir.resolve("repository");
        Path versionDir = repo.resolve("com/acme/lib/1.0-SNAPSHOT");
        Files.createDirectories(versionDir);
        Path jar = Files.writeString(versionDir.resolve("lib-1.0-20261001.204500-53.jar"), "jar");
        var parent = new PomDependencies.Parent("com.acme", "parent", "2.0-SNAPSHOT");

        List<Path> files = ExtensionCache.snapshotFiles(List.of(
                new ResolvedArtifact("com.acme", "lib", "1.0-20261001.204500-53", jar)),
                Optional.of(parent));

        assertEquals(List.of(jar, versionDir.resolve("lib-1.0-20261001.204500-53.pom"),
                repo.resolve("com/acme/parent/2.0-SNAPSHOT/parent-2.0-SNAPSHOT.pom")), files);
    }

    @Test
    void releaseParentIsNotFingerprinted() throws IOException {
        Path jar = artifact(dir.resolve("repository"), "g", "a", "1.0");

        assertTrue(ExtensionCache.snapshotFiles(
                List.of(new ResolvedArtifact("g", "a", "1.0", jar)),
                Optional.of(new PomDependencies.Parent("g", "parent", "1.0"))).isEmpty());
    }

    @Test
    void eachProjectHasItsOwnCacheFile() {
        Path cacheDir = Path.of("/cache");

        assertNotEquals(ExtensionCache.fileFor(cacheDir, Path.of("/work/a")),
                ExtensionCache.fileFor(cacheDir, Path.of("/work/b")));
        assertEquals(cacheDir, ExtensionCache.fileFor(cacheDir, Path.of("/work/a")).getParent());
    }

    @Test
    void hashFollowsThePomContent() {
        assertEquals(ExtensionCache.hash("<project/>"), ExtensionCache.hash("<project/>"));
        assertNotEquals(ExtensionCache.hash("<project/>"), ExtensionCache.hash("<project> </project>"));
    }

    private static Path artifact(Path repo, String groupId, String artifactId, String version)
            throws IOException {
        Path versionDir = repo.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(versionDir);
        Path jar = versionDir.resolve(artifactId + "-" + version + ".jar");
        Files.writeString(jar, "jar");
        return jar;
    }
}
