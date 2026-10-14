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

import io.vidocq.runtime.maven.EnrichedJars;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Decides which bricks must run their code generation on a dependency jar, from the hints they declare. Reads the jar
 * once. A directory (a reactor module, compiled by the build itself) is never selected. A {@code class-bytes} hint
 * does not match a jar that defines classes under it: an API ({@code jakarta.servlet-api} holding
 * {@code jakarta/servlet/annotation/WebListener}) is not a user of its own annotations.
 */
public final class DependencyCodegenSelection {

    private static final String VERSIONS = "META-INF/versions/";

    private DependencyCodegenSelection() {
    }

    /** Why a brick wants a jar, e.g. {@code resource META-INF/web-fragment.xml}. */
    public record Match(DependencyCodegenHints brick, String reason) {
    }

    /** A brick skipped because the jar carries its marker. */
    public record Skip(DependencyCodegenHints brick, String marker) {
    }

    /**
     * The outcome for one jar.
     *
     * @param signed whether the jar carries a signature, which a rewrite would invalidate
     */
    public record Decision(Path jar, List<Match> matches, List<Skip> skips, boolean signed) {

        public Decision {
            matches = List.copyOf(matches);
            skips = List.copyOf(skips);
        }

        public boolean selected() {
            return !matches.isEmpty();
        }
    }

    public static Decision decide(Path jar, List<DependencyCodegenHints> bricks) throws IOException {
        if (Files.isDirectory(jar)) {
            return new Decision(jar, List.of(), List.of(), false);
        }
        var matches = new ArrayList<Match>();
        var skips = new ArrayList<Skip>();
        boolean signed = false;
        var names = new HashSet<String>();
        var pending = new LinkedHashMap<DependencyCodegenHints, List<String>>();
        var classMatches = new LinkedHashMap<DependencyCodegenHints, String>();
        try (var file = new JarFile(jar.toFile())) {
            for (var e : Collections.list(file.entries())) {
                names.add(e.getName());
                signed |= EnrichedJars.isSignature(e.getName());
            }
            for (var brick : bricks) {
                String marker = brick.markers().stream().filter(names::contains).findFirst().orElse(null);
                if (marker != null) {
                    skips.add(new Skip(brick, marker));
                    continue;
                }
                String resource = brick.resources().stream().filter(names::contains).findFirst().orElse(null);
                if (resource != null) {
                    matches.add(new Match(brick, "resource " + resource));
                } else {
                    List<String> needles = brick.classBytes().stream()
                            .filter(hint -> names.stream().noneMatch(n -> n.startsWith(hint))).toList();
                    if (!needles.isEmpty()) {
                        pending.put(brick, needles);
                    }
                }
            }
            if (!pending.isEmpty()) {
                for (var e : Collections.list(file.entries())) {
                    String name = e.getName();
                    if (e.isDirectory() || !name.endsWith(".class") || name.startsWith(VERSIONS)) {
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream in = file.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                    for (var p : pending.entrySet()) {
                        var brick = p.getKey();
                        if (classMatches.containsKey(brick)) {
                            continue;
                        }
                        for (String needle : p.getValue()) {
                            if (indexOf(bytes, needle.getBytes(StandardCharsets.UTF_8)) >= 0) {
                                classMatches.put(brick, "class bytes " + needle + " in " + name);
                                break;
                            }
                        }
                    }
                    if (classMatches.size() == pending.size()) {
                        break;
                    }
                }
            }
        }
        // keep the bricks' declaration order whatever the kind of hint that matched
        var ordered = new ArrayList<Match>();
        for (var brick : bricks) {
            matches.stream().filter(m -> m.brick() == brick).findFirst().ifPresentOrElse(ordered::add, () -> {
                String reason = classMatches.get(brick);
                if (reason != null) {
                    ordered.add(new Match(brick, reason));
                }
            });
        }
        return new Decision(jar, ordered, skips, signed);
    }

    /**
     * {@link #decide(Path, List)}, memoized in {@code memo}: one small file per (jar, hints), named by the SHA-256 of
     * the jar's SHA-256 and the hints' digest, holding the decision. A jar is so searched once per machine for given
     * hints, instead of on every build and every {@code vidocq:dev} recompile; the memo is content-addressed, so it
     * never needs invalidating. A damaged entry is searched again.
     *
     * @param memo  the memo directory, {@code <cacheDir>/selection}
     * @param store whether a decision searched now is written; {@code false} for a read-only lookup
     */
    public static Decision decide(Path jar, List<DependencyCodegenHints> bricks, Path memo, boolean store)
            throws IOException {
        if (Files.isDirectory(jar) || bricks.isEmpty()) {
            return decide(jar, bricks);
        }
        Path entry = memo.resolve(memoKey(jar, bricks));
        if (Files.isRegularFile(entry)) {
            try {
                Optional<Decision> memoized = parse(jar, bricks, Files.readAllLines(entry, StandardCharsets.UTF_8));
                if (memoized.isPresent()) {
                    return memoized.get();
                }
            } catch (NoSuchFileException vanished) {
                // another build replaced it meanwhile: search
            }
        }
        Decision decision = decide(jar, bricks);
        if (store) {
            write(entry, format(decision, bricks));
        }
        return decision;
    }

    /** SHA-256 over the jar's SHA-256 and what of the hints decides: name, resources, class bytes, markers. */
    private static String memoKey(Path jar, List<DependencyCodegenHints> bricks) throws IOException {
        MessageDigest jarDigest = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(jar), jarDigest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        StringBuilder text = new StringBuilder("jar ").append(HexFormat.of().formatHex(jarDigest.digest()));
        for (DependencyCodegenHints brick : bricks) {
            text.append("\nbrick ").append(brick.brick())
                    .append("\nresources ").append(String.join(",", brick.resources()))
                    .append("\nclass-bytes ").append(String.join(",", brick.classBytes()))
                    .append("\nmarkers ").append(String.join(",", brick.markers()));
        }
        return HexFormat.of().formatHex(sha256().digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code signed true|false}, then {@code match <brick index> <reason>} and {@code skip <brick index> <marker>}. */
    private static List<String> format(Decision decision, List<DependencyCodegenHints> bricks) {
        List<String> lines = new ArrayList<>();
        lines.add("signed " + decision.signed());
        decision.matches().forEach(m -> lines.add("match " + bricks.indexOf(m.brick()) + " " + m.reason()));
        decision.skips().forEach(k -> lines.add("skip " + bricks.indexOf(k.brick()) + " " + k.marker()));
        return lines;
    }

    /** The decision {@link #format} wrote, with the bricks' own hints; empty when the lines are not such a file. */
    private static Optional<Decision> parse(Path jar, List<DependencyCodegenHints> bricks, List<String> lines) {
        if (lines.isEmpty() || !lines.getFirst().matches("signed (true|false)")) {
            return Optional.empty();
        }
        boolean signed = lines.getFirst().equals("signed true");
        var matches = new ArrayList<Match>();
        var skips = new ArrayList<Skip>();
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(" ", 3);
            if (parts.length < 3 || !parts[1].matches("[0-9]{1,6}") || Integer.parseInt(parts[1]) >= bricks.size()) {
                return Optional.empty();
            }
            DependencyCodegenHints brick = bricks.get(Integer.parseInt(parts[1]));
            switch (parts[0]) {
                case "match" -> matches.add(new Match(brick, parts[2]));
                case "skip" -> skips.add(new Skip(brick, parts[2]));
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Decision(jar, matches, skips, signed));
    }

    /** Writes {@code entry} through a temporary file and an atomic move, so a reader never sees half of it. */
    private static void write(Path entry, List<String> lines) throws IOException {
        Files.createDirectories(entry.getParent());
        Path temp = Files.createTempFile(entry.getParent(), entry.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, entry, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
