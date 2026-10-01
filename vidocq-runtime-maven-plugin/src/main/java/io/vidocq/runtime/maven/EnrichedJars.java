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

import io.vidocq.vauban.maven.enhance.ModuleInfoRewriter;
import io.vidocq.vauban.maven.enhance.VaubanModuleReferences;
import io.vidocq.vauban.maven.modularize.ModularizedJars;
import io.vidocq.vauban.maven.modularize.Modularizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The enriched copy of a scanned dependency jar: the jar with the classes {@code vidocq:generate} generated for it —
 * its {@code _VaubanComponents}, client proxies and intercepted subclasses — and a descriptor that declares them, so
 * the container finds the providers through {@code ServiceLoader} like any other.
 *
 * <p>The copies live in {@code target/vidocq-enriched/} under the original file names, and every launch shape puts
 * {@link #resolve the copy} on its path in place of the original: {@code vidocq:dev}, {@code vidocq:run},
 * {@code vidocq:package} and {@code vidocq:jlink}. A jar without a descriptor is first given one, an
 * {@code open module}, by {@link Modularizer#synthesizeOne}. The copy is a modified jar: its signature files are
 * dropped and its manifest names where it comes from.
 */
public final class EnrichedJars {

    /** Directory under {@code target/} holding the enriched copies, under the original file names. */
    public static final String DIR_NAME = "vidocq-enriched";
    static final String SERVICE_FILE = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";
    static final String MODULE_INFO = "module-info.class";
    static final String ENRICHED_FROM = "Vidocq-Enriched-From";
    static final String ENRICHED_DIGEST = "Vidocq-Enriched-Digest";
    /** A digest of everything the copy was written from, so a run with the same inputs leaves it alone. */
    static final String ENRICHED_INPUTS = "Vidocq-Enriched-Inputs";
    private static final String PROVIDER_CLASS_FILE = "_VaubanComponents.class";

    /** One scanned dependency: the original jar, its artifact id and {@code groupId:artifactId:version}. */
    public record Scanned(Path originalJar, String artifactId, String coordinates) {}

    private EnrichedJars() {}

    /** {@code target/vidocq-enriched}, existing or not. */
    public static Path root(Path buildDir) {
        return buildDir.resolve(DIR_NAME);
    }

    /** What a launch puts on its path for {@code originalJar}: the enriched copy, else the modularized, else it. */
    public static Path resolve(Path buildDir, Path originalJar) {
        Path candidate = root(buildDir).resolve(originalJar.getFileName().toString());
        return Files.isRegularFile(candidate) ? candidate : ModularizedJars.resolve(buildDir, originalJar);
    }

    /** Whether {@code originalJar} has an enriched copy, which then carries all its generated classes. */
    public static boolean isEnriched(Path buildDir, Path originalJar) {
        return Files.isRegularFile(root(buildDir).resolve(originalJar.getFileName().toString()));
    }

    /** Deletes every enriched copy, so a jar no longer scanned is never substituted by a stale one. */
    public static void clear(Path buildDir) throws IOException {
        Path root = root(buildDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * Writes the enriched copy of every scanned jar that has classes parked in {@code target/vidocq-patches/}. A jar
     * without a root descriptor gets a synthesized {@code open module}; one that cannot get it stays with
     * {@code --patch-module}, and {@code warn} says why.
     *
     * @param providers every dependency provider {@code vidocq:generate} wrote; each jar keeps those of its packages
     * @param closure   every dependency jar, for the descriptor synthesis
     * @return the original jars that now have an enriched copy
     */
    public static List<Path> enrichAll(Path buildDir, List<Scanned> scanned, List<String> providers,
                                       List<Path> closure, Consumer<String> info, Consumer<String> warn)
            throws IOException {
        List<Path> enriched = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (Scanned dep : scanned) {
            Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
            if (!Files.isDirectory(patchDir)) {
                continue;
            }
            Path source = ModularizedJars.resolve(buildDir, dep.originalJar());
            boolean synthesize = !hasRootDescriptor(source);
            Set<String> packages = JpmsPatches.packagesOf(source);
            List<String> own = providers.stream().filter(p -> packages.contains(packageOf(p))).toList();
            Path out = root(buildDir).resolve(dep.originalJar().getFileName().toString());
            String inputs = inputs(source, patchDir, own, dep.coordinates(), synthesize ? closure : List.of());
            if (inputs.equals(inputsOf(out))) {
                // Written by an earlier run from the same inputs: left untouched, since a running vidocq:dev
                // child holds it open, and a jar held open cannot be replaced on every platform.
                current.add(out.getFileName().toString());
                enriched.add(dep.originalJar());
                info.accept("Enriched copy of " + dep.originalJar().getFileName() + " is up to date");
                continue;
            }
            byte[] moduleInfo = null;
            if (synthesize) {
                Optional<byte[]> synthesized = Modularizer.synthesizeOne(source, closure, true, info);
                if (synthesized.isEmpty()) {
                    warn.accept(dep.artifactId() + ": no module descriptor can be given to it here, so its"
                            + " generated classes stay attached with --patch-module and its providers are not used");
                    continue;
                }
                moduleInfo = synthesized.get();
            }
            write(source, moduleInfo, patchDir, own, dep.coordinates(), dep.originalJar(), out, inputs);
            current.add(out.getFileName().toString());
            enriched.add(dep.originalJar());
            info.accept("Enriched " + dep.originalJar().getFileName() + " with its generated code (" + own.size()
                    + " provider(s)) → target/" + DIR_NAME + "/" + out.getFileName());
        }
        retainOnly(buildDir, current);
        return enriched;
    }

    /**
     * At packaging, after {@code vauban:modularize}: rebuilds the enriched copy of {@code dep} on the jar that goal
     * modularized, when there is one, so the module name and openness it was configured with are those shipped. On a
     * clean build {@code vidocq:generate} runs first and synthesizes a descriptor of its own; on a later build it
     * starts from the modularized jar already. Either way the package ends the same. A no-op when nothing changed.
     */
    public static void rebaseOnModularized(Path buildDir, Scanned dep, Consumer<String> info) throws IOException {
        Path out = root(buildDir).resolve(dep.originalJar().getFileName().toString());
        Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
        if (!Files.isRegularFile(out) || !Files.isDirectory(patchDir)
                || !ModularizedJars.isModularized(buildDir, dep.originalJar())) {
            return;
        }
        Path source = ModularizedJars.resolve(buildDir, dep.originalJar());
        if (!hasRootDescriptor(source)) {
            return;
        }
        List<String> providers = providersIn(patchDir);
        String inputs = inputs(source, patchDir, providers, dep.coordinates(), List.of());
        if (inputs.equals(inputsOf(out))) {
            return;
        }
        write(source, null, patchDir, providers, dep.coordinates(), dep.originalJar(), out, inputs);
        info.accept("Rebuilt the enriched copy of " + dep.originalJar().getFileName()
                + " on the module descriptor vauban:modularize generated");
    }

    /**
     * Writes {@code out}: {@code source} with the class files of {@code patchDir} added (an entry the jar already has
     * wins), its descriptor declaring {@code providers} and requiring what the added classes call into, a class-path
     * service file, and a manifest that names its origin. Signature files and per-entry digests are dropped.
     *
     * @param moduleInfo  the descriptor to start from when {@code source} has none at its root, else {@code null}
     * @param coordinates {@code groupId:artifactId:version} of the original, recorded in the manifest
     * @param originalJar the original jar, whose digest the manifest records
     */
    public static void write(Path source, byte[] moduleInfo, Path patchDir, List<String> providers,
                             String coordinates, Path originalJar, Path out) throws IOException {
        write(source, moduleInfo, patchDir, providers, coordinates, originalJar, out,
                inputs(source, patchDir, providers, coordinates, List.of()));
    }

    private static void write(Path source, byte[] moduleInfo, Path patchDir, List<String> providers,
                              String coordinates, Path originalJar, Path out, String inputs) throws IOException {
        Map<String, byte[]> patch = readTree(patchDir);
        Set<String> requires = VaubanModuleReferences.of(patch.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".class")).map(Map.Entry::getValue).toList());

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Manifest manifest = new Manifest();
        try (JarFile in = new JarFile(source.toFile(), false)) {
            if (in.getManifest() != null) {
                manifest = new Manifest(in.getManifest());
            }
            for (JarEntry entry : Collections.list(in.entries())) {
                if (entry.isDirectory() || entry.getName().equals(JarFile.MANIFEST_NAME)
                        || isSignature(entry.getName())) {
                    continue;
                }
                try (InputStream is = in.getInputStream(entry)) {
                    entries.put(entry.getName(), is.readAllBytes());
                }
            }
        }
        byte[] base = entries.containsKey(MODULE_INFO) ? entries.get(MODULE_INFO) : moduleInfo;
        if (base == null) {
            throw new IllegalArgumentException(source.getFileName() + " has no module descriptor to enrich");
        }
        patch.forEach(entries::putIfAbsent);
        entries.put(MODULE_INFO, ModuleInfoRewriter.addComponentProvider(base, providers, requires));
        if (!providers.isEmpty()) {
            Set<String> lines = new LinkedHashSet<>();
            if (entries.containsKey(SERVICE_FILE)) {
                new String(entries.get(SERVICE_FILE), StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(lines::add);
            }
            lines.addAll(providers);
            entries.put(SERVICE_FILE, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        manifest.getEntries().clear();
        Attributes main = manifest.getMainAttributes();
        main.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue(ENRICHED_FROM, coordinates);
        main.putValue(ENRICHED_DIGEST, "sha256:" + sha256(originalJar));
        main.putValue(ENRICHED_INPUTS, inputs);

        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), out.getFileName().toString(), ".tmp");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
            for (var e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@code META-INF/*.SF}, {@code *.RSA}, {@code *.DSA}, {@code *.EC}, {@code SIG-*}: a signature. */
    static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    /**
     * {@code sha256:…} over what a copy is written from: the source jar, the parked classes, the providers, the
     * coordinates, and, when a descriptor is synthesized, the names of the jars jdeps resolves against.
     */
    private static String inputs(Path source, Path patchDir, List<String> providers, String coordinates,
                                 List<Path> closure) throws IOException {
        MessageDigest digest = sha256();
        Consumer<String> add = s -> digest.update((s + "\n").getBytes(StandardCharsets.UTF_8));
        add.accept("source " + sha256(source));
        add.accept("coordinates " + coordinates);
        providers.stream().sorted().forEach(p -> add.accept("provider " + p));
        for (var e : readTree(patchDir).entrySet()) {
            add.accept("class " + e.getKey() + " " + HexFormat.of().formatHex(sha256().digest(e.getValue())));
        }
        closure.stream().map(p -> p.getFileName().toString()).sorted().forEach(n -> add.accept("closure " + n));
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /** The inputs stamp of an existing copy, or {@code null} when there is none or it cannot be read. */
    private static String inputsOf(Path copy) {
        if (!Files.isRegularFile(copy)) {
            return null;
        }
        try (JarFile in = new JarFile(copy.toFile(), false)) {
            return in.getManifest() == null ? null : in.getManifest().getMainAttributes().getValue(ENRICHED_INPUTS);
        } catch (IOException e) {
            return null;
        }
    }

    /** Deletes the copies this run did not keep or write: a jar no longer scanned is never substituted. */
    private static void retainOnly(Path buildDir, Set<String> fileNames) throws IOException {
        Path root = root(buildDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.filter(p -> !fileNames.contains(p.getFileName().toString())).toList()) {
                Files.delete(p);
            }
        }
    }

    /** The {@code _VaubanComponents} classes parked in {@code patchDir}, as binary names. */
    private static List<String> providersIn(Path patchDir) throws IOException {
        return readTree(patchDir).keySet().stream()
                .filter(name -> name.equals(PROVIDER_CLASS_FILE) || name.endsWith("/" + PROVIDER_CLASS_FILE))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .sorted().toList();
    }

    private static boolean hasRootDescriptor(Path jar) throws IOException {
        try (JarFile in = new JarFile(jar.toFile(), false)) {
            return in.getEntry(MODULE_INFO) != null;
        }
    }

    private static Map<String, byte[]> readTree(Path dir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(dir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String sha256(Path file) throws IOException {
        return HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(file)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
