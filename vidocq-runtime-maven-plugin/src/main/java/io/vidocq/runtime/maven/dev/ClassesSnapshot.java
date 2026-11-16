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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The compiled output of the application at one moment, for the hot swap classifier (design §2): for each class
 * root, every file by its relative path, with its size, modification time, SHA-256 and, for a class file, its
 * fingerprint. A file whose size and modification time did not move since the previous snapshot is taken from it,
 * not read again.
 */
record ClassesSnapshot(Map<Path, SequencedMap<String, Entry>> byRoot) {

    static final ClassesSnapshot EMPTY = new ClassesSnapshot(Map.of());

    /**
     * One file.
     *
     * @param fingerprint empty for a resource, or a class file the JDK cannot parse
     */
    record Entry(long size, FileTime modified, String sha256, Optional<ClassFingerprint> fingerprint) {}

    ClassesSnapshot {
        var copy = new LinkedHashMap<Path, SequencedMap<String, Entry>>();
        byRoot.forEach((root, files) -> copy.put(root, Collections.unmodifiableSequencedMap(files)));
        byRoot = Collections.unmodifiableMap(copy);
    }

    /** The files under each existing directory of {@code classRoots}; a missing root is left out. */
    static ClassesSnapshot take(List<Path> classRoots, ClassesSnapshot previous) {
        return take(classRoots, previous, ClassFingerprint::of);
    }

    // package-private for the test that counts the parses.
    static ClassesSnapshot take(List<Path> classRoots, ClassesSnapshot previous,
            Function<byte[], ClassFingerprint> fingerprint) {
        var byRoot = new LinkedHashMap<Path, SequencedMap<String, Entry>>();
        for (Path root : classRoots) {
            if (Files.isDirectory(root)) {
                byRoot.put(root, files(root, previous.byRoot.getOrDefault(root, Collections.emptySortedMap()),
                        fingerprint));
            }
        }
        return new ClassesSnapshot(byRoot);
    }

    /** Every class of every root that could be parsed, by binary name; the first root wins a duplicate. */
    SequencedMap<String, ClassFingerprint> classes() {
        var classes = new LinkedHashMap<String, ClassFingerprint>();
        byRoot.values().forEach(files -> files.values().forEach(entry -> entry.fingerprint()
                .ifPresent(f -> classes.putIfAbsent(f.binaryName(), f))));
        return classes;
    }

    private static SequencedMap<String, Entry> files(Path root, Map<String, Entry> before,
            Function<byte[], ClassFingerprint> fingerprint) {
        var files = new TreeMap<String, Entry>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                Entry old = before.get(relative);
                if (old != null && old.size() == attributes.size()
                        && old.modified().equals(attributes.lastModifiedTime())) {
                    files.put(relative, old);
                } else {
                    byte[] bytes = Files.readAllBytes(file);
                    files.put(relative, new Entry(attributes.size(), attributes.lastModifiedTime(), sha256(bytes),
                            relative.endsWith(".class") ? parse(bytes, fingerprint) : Optional.empty()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the compiled output under " + root, e);
        }
        return new LinkedHashMap<>(files);
    }

    private static Optional<ClassFingerprint> parse(byte[] bytes, Function<byte[], ClassFingerprint> fingerprint) {
        try {
            return Optional.of(fingerprint.apply(bytes));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required algorithm of every JDK", e);
        }
    }
}
