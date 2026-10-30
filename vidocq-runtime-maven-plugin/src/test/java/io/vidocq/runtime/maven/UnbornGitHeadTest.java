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

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project in a git repository without any commit yet, the beginner's {@code vidocq create … && git init &&
 * ./mvnw package}, failed in {@code git-commit-id-maven-plugin} ("Could not get HEAD Ref"), which none of its
 * {@code failOn…} switches covers. The participant skips the plugin there; the build identity is then left out,
 * as outside a repository (BUG-20261030-01).
 */
class UnbornGitHeadTest {

    @TempDir
    Path repo;

    private Path git() throws Exception {
        Path git = Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        return git;
    }

    private Path project() throws Exception {
        return Files.createDirectories(repo.resolve("apps/library"));
    }

    @Test
    void aRepositoryWithoutCommitHasAnUnbornHead() throws Exception {
        git();
        assertTrue(CodegenPathsParticipant.unbornGitHead(project()));
    }

    /** A file under {@code .git} that gives HEAD a commit: the branch ref, a packed ref, or a detached HEAD. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "refs/heads/main|0123456789abcdef0123456789abcdef01234567",
            "packed-refs|0123456789abcdef0123456789abcdef01234567 refs/heads/main",
            "HEAD|0123456789abcdef0123456789abcdef01234567"})
    void aHeadWithACommitIsNotUnborn(String file, String content) throws Exception {
        Path git = git();
        Files.writeString(git.resolve(file), content + "\n");
        assertFalse(CodegenPathsParticipant.unbornGitHead(project()));
    }

    @Test
    void noRepositoryOrAWorktreeIsLeftToThePlugin() throws Exception {
        assertFalse(CodegenPathsParticipant.unbornGitHead(project()));
        Files.writeString(repo.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/library\n");
        assertFalse(CodegenPathsParticipant.unbornGitHead(project()));
    }

    @Test
    void theParticipantSkipsTheBuildIdentityOfAProjectWithoutCommit() throws Exception {
        git();
        MavenProject unborn = projectIn(project());
        MavenProject chosen = projectIn(project());
        chosen.getProperties().setProperty(CodegenPathsParticipant.GIT_COMMIT_ID_SKIP, "false");

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(unborn, chosen)));

        assertEquals("true", unborn.getProperties().getProperty(CodegenPathsParticipant.GIT_COMMIT_ID_SKIP));
        assertEquals("false", chosen.getProperties().getProperty(CodegenPathsParticipant.GIT_COMMIT_ID_SKIP),
                "a value the project sets itself is kept");
    }

    @Test
    void theParticipantLeavesAProjectWithCommitsAlone() throws Exception {
        Path git = git();
        Files.writeString(git.resolve("refs/heads/main"), "0123456789abcdef0123456789abcdef01234567\n");
        MavenProject project = projectIn(project());

        new CodegenPathsParticipant().afterProjectsRead(session(List.of(project)));

        assertNull(project.getProperties().getProperty(CodegenPathsParticipant.GIT_COMMIT_ID_SKIP));
    }

    private static MavenProject projectIn(Path dir) {
        Model model = new Model();
        model.setGroupId("io.example");
        model.setArtifactId("library");
        model.setVersion("1.0");
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve("pom.xml").toFile());
        return project;
    }

    /** A session of {@code projects}, which is all the participant reads. */
    @SuppressWarnings("deprecation")
    private static MavenSession session(List<MavenProject> projects) {
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
        session.setProjects(projects);
        return session;
    }
}
