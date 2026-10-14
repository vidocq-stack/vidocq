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
package io.vidocq.runtime.maven.depgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Content-keyed cache of what dependency codegen produced for a jar, so each dependency version is processed once per
 * machine. An entry is {@code <dir>/<key>/files/...}, an optional {@code warnings.txt} (one line per warning) and a
 * {@code complete} marker; an entry without the marker is treated as damaged and discarded.
 */
public final class DependencyCodegenCache {

    private static final String COMPLETE = "complete";
    private static final String FILES = "files";
    private static final String WARNINGS = "warnings.txt";

    private final Path directory;

    public DependencyCodegenCache(Path directory) {
        this.directory = directory;
    }

    /** {@code cacheDir} property, else {@code $XDG_CACHE_HOME/vidocq/depgen}, else {@code ${user.home}/.cache/vidocq/depgen}. */
    public static Path directory(String cacheDirProperty, Map<String, String> env, String userHome) {
        if (cacheDirProperty != null && !cacheDirProperty.isBlank()) {
            return Path.of(cacheDirProperty);
        }
        String xdg = env.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "vidocq", "depgen");
        }
        return Path.of(userHome, ".cache", "vidocq", "depgen");
    }

    /** SHA-256 over the jar's digest, each brick ("name processorPathDigest", sorted), the release and the JDK. */
    public static String key(Path jar, List<Brick> bricks, int release) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("jar " + sha256(jar));
        for (Brick brick : bricks) {
            lines.add("brick " + brick.hints().brick() + " " + classpathDigest(brick.processorPath()));
        }
        lines.subList(1, lines.size()).sort(Comparator.naturalOrder());
        lines.add("release " + release);
        lines.add("jdk " + Runtime.version().feature());
        return hex(digest().digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    }

    /** The SHA-256 of a processor classpath: each file's SHA-256, in order. */
    public static String classpathDigest(List<Path> processorPath) throws IOException {
        MessageDigest md = digest();
        for (Path file : processorPath) {
            md.update((sha256(file) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return hex(md.digest());
    }

    public Optional<DependencyCodegen.Output> get(String key) throws IOException {
        Path entry = directory.resolve(key);
        if (!Files.exists(entry)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(entry.resolve(COMPLETE))) {
            deleteTree(entry);
            return Optional.empty();
        }
        Map<String, byte[]> files = new TreeMap<>();
        Path root = entry.resolve(FILES);
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
        }
        Path warnings = entry.resolve(WARNINGS);
        List<String> lines = Files.isRegularFile(warnings) ? Files.readAllLines(warnings, StandardCharsets.UTF_8)
                : List.of();
        return Optional.of(new DependencyCodegen.Output(files, lines));
    }

    /** Writes the entry to a temporary directory, marks it complete, then moves it into place atomically. */
    public void put(String key, DependencyCodegen.Output output) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(key);
        deleteTree(target);
        Path temp = Files.createTempDirectory(directory, key + ".tmp");
        try {
            Path root = Files.createDirectories(temp.resolve(FILES));
            for (Map.Entry<String, byte[]> file : output.files().entrySet()) {
                Path out = root.resolve(file.getKey()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Generated path escapes the cache entry: " + file.getKey());
                }
                Files.createDirectories(out.getParent());
                Files.write(out, file.getValue());
            }
            if (!output.warnings().isEmpty()) {
                // One line per warning: embedded line breaks would split it, so they become spaces.
                String text = output.warnings().stream().map(w -> w.replaceAll("\\R", " "))
                        .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
                Files.writeString(temp.resolve(WARNINGS), text, StandardCharsets.UTF_8);
            }
            // The marker goes in before the move: the rename publishes a finished entry, so a concurrent get never
            // sees a marker-less directory it would discard.
            Files.writeString(temp.resolve(COMPLETE), "");
            try {
                move(temp, target);
            } catch (FileAlreadyExistsException | java.nio.file.DirectoryNotEmptyException concurrent) {
                // Another build stored the same content-keyed entry meanwhile; ours is redundant.
                deleteTree(temp);
                return;
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(temp);
            throw e;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        md.update(Files.readAllBytes(file));
        return hex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
