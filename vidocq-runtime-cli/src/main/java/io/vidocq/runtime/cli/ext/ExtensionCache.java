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

import io.vidocq.runtime.tooling.PomDependencies;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * On-disk cache of a project's installed extensions, so that {@code vidocq extension list},
 * {@code info} and {@code doctor} only boot Maven when the answer may have changed.
 *
 * <p>An entry is keyed by the SHA-256 of the project's {@code pom.xml}. When nothing in the
 * resolution is a SNAPSHOT it is final: a release never changes. Otherwise it also records
 * the size and modification time of every SNAPSHOT JAR and POM in the local repository (and
 * of a SNAPSHOT parent POM), and stays valid only while none of them has been re-downloaded.
 * A newer SNAPSHOT still sitting in the remote repository is not seen until something
 * fetches it — {@code vidocq extension list --refresh} forces a resolution.</p>
 *
 * <p>Pure apart from {@link #fingerprint(Path)}: the text format is
 * {@code key=value} lines, fields separated by {@code |}, the path always last.</p>
 */
public final class ExtensionCache {

    private static final String SNAPSHOT = "-SNAPSHOT";
    private static final String RUNTIME_GROUP = "io.vidocq.runtime";
    private static final String APPLICATION_PARENT = "vidocq-runtime-parent";
    private static final String RUNTIME_BOM = "vidocq-runtime-bom";

    /** A SNAPSHOT fetched from a remote repository resolves to {@code 1.0-20261001.204500-53}. */
    private static final Pattern TIMESTAMPED = Pattern.compile("-\\d{8}\\.\\d{6}-\\d+$");

    /** Size and modification time of a local-repository file, {@code null} when absent. */
    public record Fingerprint(Path file, long size, long lastModified) {}

    /** What was resolved for a given {@code pom.xml}, and what it depends on. */
    public record Entry(String pomHash, List<Fingerprint> snapshots,
                        List<ProjectExtensions.Installed> extensions) {
        public Entry {
            Objects.requireNonNull(pomHash, "pomHash");
            snapshots = List.copyOf(snapshots);
            extensions = List.copyOf(extensions);
        }
    }

    private ExtensionCache() {}

    /** The cache file of the project in {@code projectDir}. */
    public static Path fileFor(Path cacheDir, Path projectDir) {
        return cacheDir.resolve(hash(projectDir.toAbsolutePath().normalize().toString()) + ".cache");
    }

    /** Hex SHA-256 of {@code text}. */
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JDK", e);
        }
    }

    /** Whether {@code entry} still answers for a project whose POM now hashes to {@code pomHash}. */
    public static boolean isValid(Entry entry, String pomHash, Function<Path, Fingerprint> probe) {
        return entry.pomHash().equals(pomHash)
                && entry.snapshots().stream().allMatch(fp -> fp.equals(probe.apply(fp.file())));
    }

    /**
     * The local-repository files a resolution depends on that may change under the same
     * version: each SNAPSHOT artifact's JAR and POM, a SNAPSHOT parent's POM, and the
     * {@code vidocq-runtime-bom} a SNAPSHOT {@code vidocq-runtime-parent} imports, which
     * versions every Vidocq artifact (Vidocq/vidocq#339).
     */
    public static List<Path> snapshotFiles(List<ResolvedArtifact> resolved,
                                           Optional<PomDependencies.Parent> parent) {
        List<Path> files = new ArrayList<>();
        for (ResolvedArtifact artifact : resolved) {
            if (isSnapshot(artifact.version())) {
                files.add(artifact.file());
                files.add(pomBeside(artifact));
            }
        }
        Optional<PomDependencies.Parent> snapshotParent = parent.filter(p -> isSnapshot(p.version()));
        Optional<Path> repository = snapshotParent.isPresent() ? localRepository(resolved) : Optional.empty();
        if (snapshotParent.isPresent() && repository.isPresent()) {
            PomDependencies.Parent p = snapshotParent.get();
            files.add(pomIn(repository.get(), p.groupId(), p.artifactId(), p.version()));
            if (p.groupId().equals(RUNTIME_GROUP) && p.artifactId().equals(APPLICATION_PARENT)) {
                files.add(pomIn(repository.get(), RUNTIME_GROUP, RUNTIME_BOM, p.version()));
            }
        }
        return files;
    }

    private static Path pomIn(Path repository, String groupId, String artifactId, String version) {
        return repository.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
    }

    /** Fingerprint of {@code file}, or {@code null} when it does not exist. */
    public static Fingerprint fingerprint(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new Fingerprint(file, attrs.size(), attrs.lastModifiedTime().toMillis());
        } catch (IOException _) {
            return null;
        }
    }

    public static String write(Entry entry) {
        StringBuilder out = new StringBuilder("pomHash=").append(entry.pomHash()).append('\n');
        for (Fingerprint fp : entry.snapshots()) {
            out.append("snapshot=").append(fp.size()).append('|').append(fp.lastModified())
                    .append('|').append(fp.file()).append('\n');
        }
        for (ProjectExtensions.Installed ext : entry.extensions()) {
            ResolvedArtifact a = ext.artifact();
            out.append("extension=").append(ext.id()).append('|').append(ext.direct())
                    .append('|').append(a.groupId()).append('|').append(a.artifactId())
                    .append('|').append(a.version()).append('|').append(a.file()).append('\n');
        }
        return out.toString();
    }

    /** Parses {@link #write(Entry)}'s output; empty when it is not a cache entry. */
    public static Optional<Entry> read(String text) {
        String pomHash = null;
        List<Fingerprint> snapshots = new ArrayList<>();
        List<ProjectExtensions.Installed> extensions = new ArrayList<>();
        try {
            for (String line : text.split("\\R")) {
                int eq = line.indexOf('=');
                if (line.isBlank()) {
                    continue;
                }
                if (eq < 0) {
                    return Optional.empty();
                }
                String value = line.substring(eq + 1);
                switch (line.substring(0, eq)) {
                    case "pomHash" -> pomHash = value;
                    case "snapshot" -> {
                        String[] f = value.split("\\|", 3);
                        snapshots.add(new Fingerprint(Path.of(f[2]),
                                Long.parseLong(f[0]), Long.parseLong(f[1])));
                    }
                    case "extension" -> {
                        String[] f = value.split("\\|", 6);
                        extensions.add(new ProjectExtensions.Installed(f[0],
                                new ResolvedArtifact(f[2], f[3], f[4], Path.of(f[5])),
                                Boolean.parseBoolean(f[1])));
                    }
                    default -> { return Optional.empty(); }
                }
            }
        } catch (RuntimeException _) {
            return Optional.empty();
        }
        return pomHash == null ? Optional.empty()
                : Optional.of(new Entry(pomHash, snapshots, extensions));
    }

    private static boolean isSnapshot(String version) {
        return version.endsWith(SNAPSHOT) || TIMESTAMPED.matcher(version).find();
    }

    /** {@code 1.0-20261001.204500-53} → {@code 1.0-SNAPSHOT}, the local directory name. */
    private static String baseVersion(String version) {
        return TIMESTAMPED.matcher(version).replaceFirst(SNAPSHOT);
    }

    private static Path pomBeside(ResolvedArtifact artifact) {
        String jar = artifact.file().getFileName().toString();
        int dot = jar.lastIndexOf('.');
        return artifact.file().resolveSibling((dot < 0 ? jar : jar.substring(0, dot)) + ".pom");
    }

    /**
     * The local repository root, derived from an artifact laid out as
     * {@code <root>/<group/path>/<artifactId>/<version>/<file>}.
     */
    private static Optional<Path> localRepository(List<ResolvedArtifact> resolved) {
        for (ResolvedArtifact a : resolved) {
            Path root = repositoryRoot(a);
            if (root != null) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
    }

    /** The repository root {@code a} lives under, or {@code null} when its file is not laid out as Maven does. */
    private static Path repositoryRoot(ResolvedArtifact a) {
        Path versionDir = a.file().getParent();
        if (versionDir == null || !baseVersion(a.version()).equals(name(versionDir))) {
            return null;
        }
        Path dir = versionDir.getParent();
        if (dir == null || !a.artifactId().equals(name(dir))) {
            return null;
        }
        String[] group = a.groupId().split("\\.");
        boolean matches = true;
        for (int i = group.length - 1; i >= 0 && matches; i--) {
            dir = dir.getParent();
            matches = dir != null && group[i].equals(name(dir));
        }
        return matches ? dir.getParent() : null;
    }

    private static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }
}
