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

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wires the codegen bundles of a project's extensions once Maven has read the projects, before any goal runs
 * (Vidocq/vidocq#339, evolution 2). Maven loads it only when the plugin is declared with
 * {@code <extensions>true</extensions>}, which {@code vidocq-runtime-parent} does from 0.4.0; IntelliJ IDEA's Maven
 * import runs it too, so the IDE's processor path is the build's.
 *
 * <p>Declared in {@code META-INF/plexus/components.xml}, not with {@code @Named}: Maven 3.9.9 reads a Sisu index
 * with an ASM too old for Java 25 class files and skips the component without a word.</p>
 */
public final class CodegenPathsParticipant extends AbstractMavenLifecycleParticipant {

    /** The property that skips {@code git-commit-id-maven-plugin}, which writes the build identity. */
    static final String GIT_COMMIT_ID_SKIP = "maven.gitcommitid.skip";

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            CodegenPaths.wire(project.getModel());
            skipBuildIdentityWithoutCommit(project);
        }
    }

    /**
     * In a git repository without any commit yet, {@code git-commit-id-maven-plugin} fails the build ("Could not get
     * HEAD Ref"), whatever its {@code failOn…} switches say: the build identity is skipped there, as it is left out
     * outside a repository (BUG-20261030-01). A value the project sets itself is kept.
     */
    private static void skipBuildIdentityWithoutCommit(MavenProject project) {
        if (project.getFile() == null || project.getProperties().containsKey(GIT_COMMIT_ID_SKIP)) {
            return;
        }
        if (unbornGitHead(project.getFile().toPath().toAbsolutePath().getParent())) {
            project.getProperties().setProperty(GIT_COMMIT_ID_SKIP, "true");
        }
    }

    /**
     * Whether {@code dir} lies in a git repository whose {@code HEAD} names a branch without any commit: neither a
     * file under {@code refs/} nor a line of {@code packed-refs}. A {@code .git} file (worktree, submodule) and any
     * read failure are left to the plugin.
     */
    static boolean unbornGitHead(Path dir) {
        for (Path current = dir; current != null; current = current.getParent()) {
            Path git = current.resolve(".git");
            if (Files.isRegularFile(git)) {
                return false;
            }
            if (Files.isDirectory(git)) {
                return unborn(git);
            }
        }
        return false;
    }

    private static boolean unborn(Path git) {
        try {
            String head = Files.readString(git.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (!head.startsWith("ref:")) {
                return false;
            }
            String ref = head.substring("ref:".length()).trim();
            if (Files.exists(git.resolve(ref))) {
                return false;
            }
            Path packed = git.resolve("packed-refs");
            if (Files.exists(packed)) {
                for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    if (line.endsWith(" " + ref)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
