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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The commit a working directory is at, read from {@code .git} without running {@code git} (Vidocq/vidocq#380):
 * {@code HEAD}, the loose refs and {@code packed-refs}, through the {@code .git} file of a linked worktree
 * ({@code gitdir:} and {@code commondir}) or of a submodule, in a shallow clone as well.
 *
 * @param commit  the full hash of the commit HEAD is at
 * @param branch  the branch HEAD is on, without {@code refs/heads/}, or {@code null} when HEAD is detached
 * @param shallow whether the clone is shallow ({@code .git/shallow})
 */
public record GitHead(String commit, String branch, boolean shallow) {

    private static final String REF = "ref:";
    private static final String GITDIR = "gitdir:";
    private static final String HEADS = "refs/heads/";
    /** A SHA-1 or a SHA-256 object name. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}(?:[0-9a-f]{24})?");
    /** How many symbolic refs are followed before giving up: one is the rule, a cycle must not hang the build. */
    private static final int MAX_SYMBOLIC_REFS = 5;

    /**
     * The head of the repository {@code dir} lies in; empty when there is none, when HEAD cannot be read, or when
     * HEAD names a branch without any commit yet.
     */
    public static Optional<GitHead> read(Path dir) {
        try {
            Optional<Path> gitDir = gitDirectory(dir.toAbsolutePath());
            if (gitDir.isEmpty()) {
                return Optional.empty();
            }
            return read(gitDir.get(), commonDirectory(gitDir.get()));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /**
     * The {@code .git} directory of the repository {@code start} lies in, found upwards: a directory, or the one a
     * {@code .git} file names (a linked worktree, a submodule).
     */
    private static Optional<Path> gitDirectory(Path start) throws IOException {
        for (Path current = start; current != null; current = current.getParent()) {
            Path git = current.resolve(".git");
            if (Files.isDirectory(git)) {
                return Optional.of(git);
            }
            if (Files.isRegularFile(git)) {
                String content = Files.readString(git, StandardCharsets.UTF_8).strip();
                if (!content.startsWith(GITDIR)) {
                    return Optional.empty();
                }
                Path target = current.resolve(content.substring(GITDIR.length()).strip()).normalize();
                return Files.isDirectory(target) ? Optional.of(target) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Where the refs and {@code packed-refs} live: what {@code commondir} names in a linked worktree, else the git directory. */
    private static Path commonDirectory(Path gitDir) throws IOException {
        Path commondir = gitDir.resolve("commondir");
        if (Files.isRegularFile(commondir)) {
            return gitDir.resolve(Files.readString(commondir, StandardCharsets.UTF_8).strip()).normalize();
        }
        return gitDir;
    }

    private static Optional<GitHead> read(Path gitDir, Path common) throws IOException {
        Path headFile = gitDir.resolve("HEAD");
        if (!Files.isRegularFile(headFile)) {
            return Optional.empty();
        }
        String head = Files.readString(headFile, StandardCharsets.UTF_8).strip();
        boolean shallow = Files.isRegularFile(common.resolve("shallow")) || Files.isRegularFile(gitDir.resolve("shallow"));
        if (head.startsWith(REF)) {
            String ref = head.substring(REF.length()).strip();
            String branch = ref.startsWith(HEADS) ? ref.substring(HEADS.length()) : ref;
            return resolve(common, ref, MAX_SYMBOLIC_REFS).map(commit -> new GitHead(commit, branch, shallow));
        }
        return hash(head).map(commit -> new GitHead(commit, null, shallow));
    }

    /** The commit {@code ref} names: its loose file first, then {@code packed-refs}; a symbolic ref is followed. */
    private static Optional<String> resolve(Path common, String ref, int remaining) throws IOException {
        if (remaining == 0) {
            return Optional.empty();
        }
        Path loose = common.resolve(ref);
        if (!Files.isRegularFile(loose)) {
            return packed(common.resolve("packed-refs"), ref);
        }
        String value = Files.readString(loose, StandardCharsets.UTF_8).strip();
        if (value.startsWith(REF)) {
            return resolve(common, value.substring(REF.length()).strip(), remaining - 1);
        }
        return hash(value);
    }

    /** The commit {@code ref} names in the {@code packed} file, when the file exists and names it. */
    private static Optional<String> packed(Path packed, String ref) throws IOException {
        if (!Files.isRegularFile(packed)) {
            return Optional.empty();
        }
        for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
            // "# pack-refs with: …" heads the file; "^<hash>" lines carry the peeled object of the tag above them.
            if (line.startsWith("#") || line.startsWith("^")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space > 0 && line.substring(space + 1).strip().equals(ref)) {
                return hash(line.substring(0, space).strip());
            }
        }
        return Optional.empty();
    }

    /** {@code value} when it is an object name, else empty. */
    private static Optional<String> hash(String value) {
        return HASH.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }
}
