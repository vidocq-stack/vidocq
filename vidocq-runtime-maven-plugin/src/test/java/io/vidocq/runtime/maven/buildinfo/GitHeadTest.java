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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#380: {@code vidocq:build-info} reads the commit of the working tree from {@code .git} itself, without
 * running {@code git}, in every layout git writes: a plain repository, packed refs, a detached HEAD, a shallow clone,
 * a linked worktree and a submodule, whose {@code .git} is a file pointing at the real directory.
 */
class GitHeadTest {

    private static final String COMMIT = "9beafc47e1c8685e0123456789abcdef01234567";
    private static final String OTHER = "e1c8685e9beafc470123456789abcdef01234567";

    @TempDir
    Path dir;

    @Test
    void aBranchWithALooseRef() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n");
        write(repo.resolve(".git/refs/heads/main"), COMMIT + "\n");

        GitHead head = GitHead.read(repo).orElseThrow();

        assertEquals(COMMIT, head.commit());
        assertEquals("main", head.branch());
        assertFalse(head.shallow());
    }

    @Test
    void aModuleBelowTheRepositoryRootIsReadFromTheRoot() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/feat/no-parent\n");
        write(repo.resolve(".git/refs/heads/feat/no-parent"), COMMIT + "\n");
        Path module = Files.createDirectories(repo.resolve("app/module"));

        GitHead head = GitHead.read(module).orElseThrow();

        assertEquals(COMMIT, head.commit());
        assertEquals("feat/no-parent", head.branch(), "a branch name keeps its slashes");
    }

    @Test
    void aBranchInPackedRefs() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n");
        write(repo.resolve(".git/packed-refs"), "# pack-refs with: peeled fully-peeled sorted \n"
                + OTHER + " refs/heads/other\n"
                + COMMIT + " refs/heads/main\n"
                + "^" + OTHER + "\n"
                + OTHER + " refs/tags/v1\n");

        GitHead head = GitHead.read(repo).orElseThrow();

        assertEquals(COMMIT, head.commit());
        assertEquals("main", head.branch());
    }

    @Test
    void aLooseRefWinsOverPackedRefs() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n");
        write(repo.resolve(".git/refs/heads/main"), COMMIT + "\n");
        write(repo.resolve(".git/packed-refs"), OTHER + " refs/heads/main\n");

        assertEquals(COMMIT, GitHead.read(repo).orElseThrow().commit());
    }

    @Test
    void aDetachedHeadHasNoBranch() throws IOException {
        Path repo = repository(dir.resolve("repo"), COMMIT + "\n");

        GitHead head = GitHead.read(repo).orElseThrow();

        assertEquals(COMMIT, head.commit());
        assertNull(head.branch());
    }

    @Test
    void aShallowCloneSaysSo() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n");
        write(repo.resolve(".git/refs/heads/main"), COMMIT + "\n");
        write(repo.resolve(".git/shallow"), COMMIT + "\n");

        assertTrue(GitHead.read(repo).orElseThrow().shallow());
    }

    /** {@code git worktree add}: {@code .git} is a file, HEAD lives under the main repository's {@code worktrees/}. */
    @Test
    void aLinkedWorktreeReadsItsOwnHeadAndTheCommonRefs() throws IOException {
        Path main = repository(dir.resolve("main"), "ref: refs/heads/main\n");
        write(main.resolve(".git/refs/heads/main"), OTHER + "\n");
        write(main.resolve(".git/refs/heads/feature"), COMMIT + "\n");
        Path gitdir = main.resolve(".git/worktrees/wt");
        write(gitdir.resolve("HEAD"), "ref: refs/heads/feature\n");
        write(gitdir.resolve("commondir"), "../..\n");
        Path worktree = Files.createDirectories(dir.resolve("wt"));
        write(worktree.resolve(".git"), "gitdir: " + gitdir.toAbsolutePath() + "\n");

        GitHead head = GitHead.read(worktree).orElseThrow();

        assertEquals(COMMIT, head.commit());
        assertEquals("feature", head.branch());
    }

    /** A submodule's {@code .git} file points, relatively, into the superproject's {@code .git/modules/}. */
    @Test
    void aSubmoduleReadsTheModulesDirectoryOfItsSuperproject() throws IOException {
        Path superproject = repository(dir.resolve("super"), "ref: refs/heads/main\n");
        write(superproject.resolve(".git/refs/heads/main"), OTHER + "\n");
        Path modules = superproject.resolve(".git/modules/lib");
        write(modules.resolve("HEAD"), "ref: refs/heads/main\n");
        write(modules.resolve("refs/heads/main"), COMMIT + "\n");
        Path submodule = Files.createDirectories(superproject.resolve("lib"));
        write(submodule.resolve(".git"), "gitdir: ../.git/modules/lib\n");

        GitHead head = GitHead.read(submodule).orElseThrow();

        assertEquals(COMMIT, head.commit());
        assertEquals("main", head.branch());
    }

    @Test
    void anUnbornBranchIsNoCommit() throws IOException {
        Path repo = repository(dir.resolve("repo"), "ref: refs/heads/main\n");

        assertEquals(Optional.empty(), GitHead.read(repo));
    }

    @Test
    void noRepositoryIsNoCommit() throws IOException {
        assertEquals(Optional.empty(), GitHead.read(Files.createDirectories(dir.resolve("plain"))));
    }

    @Test
    void anUnreadableHeadIsNoCommit() throws IOException {
        Path noHead = Files.createDirectories(dir.resolve("nohead/.git")).getParent();
        Path garbage = repository(dir.resolve("garbage"), "not a ref, not a hash\n");
        Path danglingLink = Files.createDirectories(dir.resolve("dangling"));
        write(danglingLink.resolve(".git"), "gitdir: /no/such/directory\n");

        assertEquals(Optional.empty(), GitHead.read(noHead));
        assertEquals(Optional.empty(), GitHead.read(garbage));
        assertEquals(Optional.empty(), GitHead.read(danglingLink));
    }

    /** A repository at {@code root} whose {@code .git/HEAD} holds {@code head}. */
    private static Path repository(Path root, String head) throws IOException {
        write(root.resolve(".git/HEAD"), head);
        return root;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
