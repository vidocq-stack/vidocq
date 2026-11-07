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
package io.vidocq.runtime.maven.buildinfo;

import io.vidocq.runtime.core.banner.BuildInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Vidocq/vidocq#380: what {@code vidocq:build-info} writes, the identity {@code git-commit-id-maven-plugin} wrote
 * under {@code vidocq-runtime-parent}, read by the runtime's {@link BuildInfo}. Without a repository the build does not
 * fail: the commit reads {@value BuildIdentity#UNVERSIONED}, and the build time is still written.
 */
class BuildIdentityTest {

    private static final String COMMIT = "9beafc47e1c8685e0123456789abcdef01234567";
    private static final Instant BUILT = Instant.parse("2026-11-07T10:00:00Z");

    @TempDir
    Path dir;

    @Test
    void aRepositoryGivesTheCommitTheBranchAndTheDirtyFlag() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n", COMMIT);

        BuildIdentity identity = BuildIdentity.collect(repo, "1.0.0-SNAPSHOT", BUILT, d -> Optional.of(true));

        assertEquals(COMMIT, identity.commit());
        assertEquals("9beafc47", identity.abbreviatedCommit());
        assertEquals("main", identity.branch());
        assertEquals(Boolean.TRUE, identity.dirty());
        assertEquals("""
                git.branch=main
                git.build.time=2026-11-07T10:00:00Z
                git.build.version=1.0.0-SNAPSHOT
                git.commit.id.abbrev=9beafc47
                git.commit.id.full=9beafc47e1c8685e0123456789abcdef01234567
                git.dirty=true
                """, identity.properties());
    }

    @Test
    void withoutARepositoryTheBuildIsUnversionedAndStillDated() throws IOException {
        Path plain = Files.createDirectories(dir.resolve("plain"));

        BuildIdentity identity = BuildIdentity.collect(plain, "1.0", BUILT, d -> {
            throw new AssertionError("git is not asked about a directory that is no repository");
        });

        assertEquals("unversionned", BuildIdentity.UNVERSIONED);
        assertEquals(BuildIdentity.UNVERSIONED, identity.commit());
        assertEquals(BuildIdentity.UNVERSIONED, identity.abbreviatedCommit());
        assertEquals(BuildIdentity.UNVERSIONED, identity.branch());
        assertNull(identity.dirty());
        assertEquals("""
                git.branch=unversionned
                git.build.time=2026-11-07T10:00:00Z
                git.build.version=1.0
                git.commit.id.abbrev=unversionned
                git.commit.id.full=unversionned
                """, identity.properties());
    }

    @Test
    void aDetachedHeadIsNamedAsGitNamesIt() throws IOException {
        Path repo = repository(dir.resolve("repo"), COMMIT + "\n", null);

        BuildIdentity identity = BuildIdentity.collect(repo, "1.0", BUILT, GitStatus.NONE);

        assertEquals(COMMIT, identity.commit());
        assertEquals("HEAD", identity.branch());
    }

    @Test
    void theDirtyFlagIsLeftOutWhenGitCannotTell() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n", COMMIT);

        BuildIdentity identity = BuildIdentity.collect(repo, "1.0", BUILT, GitStatus.NONE);

        assertNull(identity.dirty());
        assertFalse(identity.properties().contains("git.dirty"), identity.properties());
    }

    /** Reproducible builds: {@code project.build.outputTimestamp} (ISO-8601 or epoch seconds) is the build time. */
    @Test
    void theBuildTimeIsTheOutputTimestampWhenTheProjectSetsOne() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00.123Z"), ZoneOffset.UTC);

        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), BuildIdentity.buildTime("2026-01-02T03:04:05Z", clock));
        assertEquals(Instant.parse("2026-01-02T02:04:05Z"), BuildIdentity.buildTime("2026-01-02T03:04:05+01:00", clock));
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), BuildIdentity.buildTime("1700000000", clock));
        assertEquals(Instant.parse("2026-11-07T10:00:00Z"), BuildIdentity.buildTime(null, clock), "now, to the second");
        assertEquals(Instant.parse("2026-11-07T10:00:00Z"), BuildIdentity.buildTime("  ", clock));
        assertEquals(Instant.parse("2026-11-07T10:00:00Z"), BuildIdentity.buildTime("not a date", clock));
    }

    /** The file is the one the startup banner reads, in the archive of the application. */
    @Test
    void theFileIsWhatTheRuntimeReads() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n", COMMIT);
        Path classes = dir.resolve("classes");
        Path file = classes.resolve(BuildInfo.resource("todo"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, BuildIdentity.collect(repo, "1.0.0-SNAPSHOT", BUILT, d -> Optional.of(false)).properties());

        BuildInfo read = BuildInfo.read(null, null, classes.toUri());

        assertEquals("1.0.0-SNAPSHOT", read.version());
        assertEquals("9beafc47", read.commit());
        assertEquals(Boolean.FALSE, read.dirty());
        assertEquals(BUILT, read.builtAt());
    }

    @Test
    void anUnversionedFileReadsUnversionedInTheRuntime() throws IOException {
        Path classes = dir.resolve("classes");
        Path file = classes.resolve(BuildInfo.resource("todo"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, BuildIdentity.collect(dir.resolve("plain"), "1.0.0-SNAPSHOT", BUILT, GitStatus.NONE)
                .properties());

        BuildInfo read = BuildInfo.read(null, null, classes.toUri());

        assertEquals(BuildInfo.UNVERSIONED, read.commit());
        assertEquals(BUILT, read.builtAt());
        assertEquals(BuildIdentity.UNVERSIONED, BuildInfo.UNVERSIONED, "the plugin writes the word the runtime shows");
    }

    /** The dirty flag comes from {@code git status --porcelain}, when the command is there and answers. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theGitCommandTellsWhetherTheTreeIsDirty() throws IOException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path dirty = script(dir.resolve("git-dirty"), "#!/bin/sh\necho ' M src/Main.java'\n");
        Path clean = script(dir.resolve("git-clean"), "#!/bin/sh\nexit 0\n");
        Path failing = script(dir.resolve("git-failing"), "#!/bin/sh\necho 'fatal: not a git repository' >&2\nexit 128\n");

        assertEquals(Optional.of(true), GitStatus.command(dirty.toString()).dirty(repo));
        assertEquals(Optional.of(false), GitStatus.command(clean.toString()).dirty(repo));
        assertEquals(Optional.empty(), GitStatus.command(failing.toString()).dirty(repo));
        assertEquals(Optional.empty(), GitStatus.command(dir.resolve("no-such-git").toString()).dirty(repo));
    }

    private static Path repository(Path root, String head, String commitOfMain) throws IOException {
        write(root.resolve(".git/HEAD"), head);
        if (commitOfMain != null) {
            write(root.resolve(".git/refs/heads/main"), commitOfMain + "\n");
        }
        return root;
    }

    private static Path script(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
