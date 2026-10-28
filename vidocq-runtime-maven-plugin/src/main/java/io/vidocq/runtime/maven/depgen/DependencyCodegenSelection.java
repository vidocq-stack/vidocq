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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/**
 * Decides which bricks must run their code generation on a dependency jar, from the hints they declare. Reads the jar
 * once. A directory (a reactor module, compiled by the build itself) is never selected. A {@code class-bytes} hint
 * does not match a jar that defines classes under it: an API ({@code jakarta.servlet-api} holding
 * {@code jakarta/servlet/annotation/WebListener}) is not a user of its own annotations.
 */
public final class DependencyCodegenSelection {

    private static final String SERVICES = "META-INF/services/";
    private static final String CLASS_SUFFIX = ".class";

    /**
     * The first line hashed into every memo key. Bump it whenever the selection rules or the memo's content change,
     * so that the entries older rules wrote are never read again.
     */
    static final String MEMO_FORMAT = "selection v2";

    private DependencyCodegenSelection() {
    }

    /**
     * Why a brick wants a jar, e.g. {@code resource META-INF/web-fragment.xml}.
     *
     * @param classes the binary names of the top-level classes its processors must see, sorted
     */
    public record Match(DependencyCodegenHints brick, String reason, List<String> classes) {
        public Match {
            classes = List.copyOf(classes);
        }

        /** A match with no class to process. */
        public Match(DependencyCodegenHints brick, String reason) {
            this(brick, reason, List.of());
        }
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

    /**
     * The bricks' decision for {@code jar}, with the classes each selecting brick's processors must see: the
     * top-level classes whose class file holds one of its {@code class-bytes} hints, and those named in the service
     * files among its matched {@code resources} (spec §4.1). Base entries only.
     */
    public static Decision decide(Path jar, List<DependencyCodegenHints> bricks) throws IOException {
        if (Files.isDirectory(jar)) {
            return new Decision(jar, List.of(), List.of(), false);
        }
        var skips = new ArrayList<Skip>();
        boolean signed = false;
        var names = new LinkedHashSet<String>();
        var resourceReasons = new LinkedHashMap<DependencyCodegenHints, String>();
        var needles = new LinkedHashMap<DependencyCodegenHints, List<String>>();
        var classReasons = new LinkedHashMap<DependencyCodegenHints, String>();
        var classes = new LinkedHashMap<DependencyCodegenHints, TreeSet<String>>();
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
                classes.put(brick, new TreeSet<>());
                brick.resources().stream().filter(names::contains).findFirst()
                        .ifPresent(resource -> resourceReasons.put(brick, "resource " + resource));
                for (String resource : brick.resources()) {
                    if (names.contains(resource) && resource.startsWith(SERVICES)) {
                        classes.get(brick).addAll(listedClasses(file, resource, names));
                    }
                }
                List<String> own = brick.classBytes().stream()
                        .filter(hint -> names.stream().noneMatch(n -> n.startsWith(hint))).toList();
                if (!own.isEmpty()) {
                    needles.put(brick, own);
                }
            }
            if (!needles.isEmpty()) {
                for (var e : Collections.list(file.entries())) {
                    String name = e.getName();
                    String topLevel = topLevelClass(name);
                    if (e.isDirectory() || topLevel == null) {
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream in = file.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                    for (var n : needles.entrySet()) {
                        for (String needle : n.getValue()) {
                            if (indexOf(bytes, needle.getBytes(StandardCharsets.UTF_8)) >= 0) {
                                classReasons.putIfAbsent(n.getKey(), "class bytes " + needle + " in " + name);
                                classes.get(n.getKey()).add(topLevel);
                                break;
                            }
                        }
                    }
                }
            }
        }
        // keep the bricks' declaration order whatever the kind of hint that matched
        var ordered = new ArrayList<Match>();
        for (var brick : bricks) {
            String reason = resourceReasons.containsKey(brick) ? resourceReasons.get(brick) : classReasons.get(brick);
            if (reason != null) {
                ordered.add(new Match(brick, reason, List.copyOf(classes.get(brick))));
            }
        }
        return new Decision(jar, ordered, skips, signed);
    }

    /**
     * The binary name of the top-level class of a base class entry, e.g. {@code com.acme.Outer} for
     * {@code com/acme/Outer$Inner.class}; {@code null} for anything else, versioned entries and module or package
     * infos included.
     */
    private static String topLevelClass(String entry) {
        if (!entry.endsWith(CLASS_SUFFIX) || entry.startsWith("META-INF/")) {
            return null;
        }
        String binary = entry.substring(0, entry.length() - CLASS_SUFFIX.length());
        int slash = binary.lastIndexOf('/');
        String simple = binary.substring(slash + 1);
        if (simple.equals("module-info") || simple.equals("package-info")) {
            return null;
        }
        int dollar = simple.indexOf('$');
        String top = dollar > 0 ? binary.substring(0, slash + 1 + dollar) : binary;
        return top.replace('/', '.');
    }

    /** The top-level classes of the jar that a service file lists, one binary name per non-comment line. */
    private static List<String> listedClasses(JarFile file, String resource, Set<String> names) throws IOException {
        String text;
        try (InputStream in = file.getInputStream(file.getEntry(resource))) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> listed = new ArrayList<>();
        for (String line : text.lines().toList()) {
            int hash = line.indexOf('#');
            String name = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (!name.isEmpty() && names.contains(name.replace('.', '/') + CLASS_SUFFIX)) {
                listed.add(topLevelClass(name.replace('.', '/') + CLASS_SUFFIX));
            }
        }
        return listed;
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
        Path entry = memo.resolve(memoKey(jar, bricks, MEMO_FORMAT));
        if (Files.isRegularFile(entry)) {
            try {
                Optional<Decision> memoized = parse(jar, bricks, Files.readAllLines(entry, StandardCharsets.UTF_8));
                if (memoized.isPresent()) {
                    return memoized.get();
                }
            } catch (NoSuchFileException _) {
                // another build replaced it meanwhile: search
            }
        }
        Decision decision = decide(jar, bricks);
        if (store) {
            write(entry, format(decision, bricks));
        }
        return decision;
    }

    /**
     * SHA-256 over {@code format} ({@link #MEMO_FORMAT}), the jar's SHA-256 and what of the hints decides: name,
     * resources, class bytes, markers.
     */
    static String memoKey(Path jar, List<DependencyCodegenHints> bricks, String format) throws IOException {
        MessageDigest jarDigest = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(jar), jarDigest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        StringBuilder text = new StringBuilder(format).append("\njar ")
                .append(HexFormat.of().formatHex(jarDigest.digest()));
        for (DependencyCodegenHints brick : bricks) {
            text.append("\nbrick ").append(brick.brick())
                    .append("\nresources ").append(String.join(",", brick.resources()))
                    .append("\nclass-bytes ").append(String.join(",", brick.classBytes()))
                    .append("\nmarkers ").append(String.join(",", brick.markers()));
        }
        return HexFormat.of().formatHex(sha256().digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * {@code signed true|false}, then {@code match <brick index> <reason>}, each of its classes as
     * {@code class <brick index> <binary name>}, and {@code skip <brick index> <marker>}.
     */
    private static List<String> format(Decision decision, List<DependencyCodegenHints> bricks) {
        List<String> lines = new ArrayList<>();
        lines.add("signed " + decision.signed());
        for (Match m : decision.matches()) {
            lines.add("match " + bricks.indexOf(m.brick()) + " " + m.reason());
            m.classes().forEach(c -> lines.add("class " + bricks.indexOf(m.brick()) + " " + c));
        }
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
        var classes = new LinkedHashMap<DependencyCodegenHints, List<String>>();
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(" ", 3);
            if (parts.length < 3 || !parts[1].matches("\\d{1,6}") || Integer.parseInt(parts[1]) >= bricks.size()) {
                return Optional.empty();
            }
            DependencyCodegenHints brick = bricks.get(Integer.parseInt(parts[1]));
            switch (parts[0]) {
                case "match" -> matches.add(new Match(brick, parts[2]));
                case "skip" -> skips.add(new Skip(brick, parts[2]));
                case "class" -> classes.computeIfAbsent(brick, b -> new ArrayList<>()).add(parts[2]);
                default -> {
                    return Optional.empty();
                }
            }
        }
        List<Match> withClasses = matches.stream()
                .map(m -> new Match(m.brick(), m.reason(), classes.getOrDefault(m.brick(), List.of()))).toList();
        return Optional.of(new Decision(jar, withClasses, skips, signed));
    }

    /** Writes {@code entry} through a temporary file and an atomic move, so a reader never sees half of it. */
    private static void write(Path entry, List<String> lines) throws IOException {
        Files.createDirectories(entry.getParent());
        Path temp = Files.createTempFile(entry.getParent(), entry.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
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
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (startsAt(haystack, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsAt(byte[] haystack, int at, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }
}
