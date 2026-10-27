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

import io.vidocq.runtime.maven.depgen.ModuleDescriptorAugmenter;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The enriched copy of a scanned dependency jar: the jar with the classes {@code vidocq:generate} generated for it —
 * its {@code _VaubanComponents}, client proxies and intercepted subclasses — and a descriptor that declares them, so
 * the container finds the providers through {@code ServiceLoader} like any other.
 *
 * <p>The copies live in {@code target/vidocq-enriched/} under the original file names, and every launch shape puts
 * {@link #resolve the copy} on its path in place of the original: {@code vidocq:dev}, {@code vidocq:run},
 * {@code vidocq:package} and {@code vidocq:jlink}. A jar without a descriptor is first given one, an
 * {@code open module}, by {@link Modularizer#synthesizeOne}, when it gains Vauban providers; one that only gains what
 * other bricks generated for it stays an automatic module, whose {@code provides} the JDK derives from its service
 * files. The copy is a modified jar: its signature files are dropped and its manifest names where it comes from and
 * the bricks that enriched it.
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
    private static final Pattern VERSIONED_DESCRIPTOR =
            Pattern.compile("META-INF/versions/[1-9]\\d*/module-info\\.class");

    /** Names the bricks that enriched a copy, sorted, after the {@code vidocq:generate} dependency codegen pass. */
    static final String ENRICHED_BY = "Vidocq-Enriched-By";
    /**
     * Marks a copy {@code vidocq:generate} kept an automatic module because a brick selected its jar, which has no
     * descriptor: {@code vidocq:package} ships it as it is (vidocq#251).
     */
    static final String AUTOMATIC = "Vidocq-Automatic";
    private static final String AUTOMATIC_BRICK_SELECTED = "brick-selected";
    private static final String SERVICES_DIR = "META-INF/services/";
    private static final String META_INF = "META-INF/";
    private static final String CLASS_SUFFIX = ".class";

    /**
     * One scanned dependency: the original jar, its artifact id and {@code groupId:artifactId:version}, and the
     * bricks whose processors generated code for it.
     *
     * @param bricks      the names of the bricks that processed the jar, sorted; empty for Vauban alone
     * @param brickInputs lines added to the inputs digest, so that a processor change rewrites the copy, e.g.
     *                    {@code brick Foy io.vidocq.foy:foy-codegen:0.4.0 sha256:<processor classpath>}
     */
    public record Scanned(Path originalJar, String artifactId, String coordinates, List<String> bricks,
                          List<String> brickInputs, boolean selectedByBrick) {
        public Scanned {
            bricks = bricks == null ? List.of() : bricks.stream().distinct().sorted().toList();
            brickInputs = brickInputs == null ? List.of() : List.copyOf(brickInputs);
            selectedByBrick |= !bricks.isEmpty();
        }

        /** A jar bricks processed, so a brick selected it. */
        public Scanned(Path originalJar, String artifactId, String coordinates, List<String> bricks,
                       List<String> brickInputs) {
            this(originalJar, artifactId, coordinates, bricks, brickInputs, false);
        }

        /** A jar only Vauban generated code for; {@code selectedByBrick} when a brick selected it all the same. */
        public Scanned(Path originalJar, String artifactId, String coordinates, boolean selectedByBrick) {
            this(originalJar, artifactId, coordinates, List.of(), List.of(), selectedByBrick);
        }

        /** A jar only Vauban selected and generated code for. */
        public Scanned(Path originalJar, String artifactId, String coordinates) {
            this(originalJar, artifactId, coordinates, false);
        }
    }

    /** What the bricks' output needs to be merged and declared, beyond the parked files themselves. */
    private record BrickOutput(List<String> bricks, Set<String> mergedResources,
                               Function<Set<String>, Set<String>> modulesOfPackages, boolean keepAutomatic) {
        static final BrickOutput NONE = new BrickOutput(List.of(), Set.of(), packages -> Set.of(), false);
    }

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

    /** The bricks the enriched copy of {@code originalJar} names in {@code Vidocq-Enriched-By}; empty when none. */
    static List<String> enrichedBy(Path buildDir, Path originalJar) {
        String value = manifestValue(root(buildDir).resolve(originalJar.getFileName().toString()), ENRICHED_BY);
        return value == null ? List.of()
                : Stream.of(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Whether the enriched copy of {@code originalJar} is one {@code vidocq:generate} kept automatic on purpose. */
    static boolean keptAutomatic(Path buildDir, Path originalJar) {
        return AUTOMATIC_BRICK_SELECTED.equals(
                manifestValue(root(buildDir).resolve(originalJar.getFileName().toString()), AUTOMATIC));
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
     * without a root descriptor whose patch holds Vauban providers gets a synthesized {@code open module}; one that
     * cannot get it stays with {@code --patch-module}, and {@code warn} says why.
     *
     * @param providers every dependency provider {@code vidocq:generate} wrote; each jar keeps those of its packages
     * @param closure   every dependency jar, for the descriptor synthesis
     * @return the original jars that now have an enriched copy
     */
    public static List<Path> enrichAll(Path buildDir, List<Scanned> scanned, List<String> providers,
                                       List<Path> closure, Consumer<String> info, Consumer<String> warn)
            throws IOException {
        return enrichAll(buildDir, scanned, providers, closure, Set.of(), packages -> Set.of(), info, warn);
    }

    /**
     * {@link #enrichAll(Path, List, List, List, Consumer, Consumer)} for jars bricks processed too: their output,
     * parked in the same patch directory as Vauban's, joins the same copy.
     *
     * <ul>
     *   <li>A parked resource named in {@code mergedResources} is merged line by line with the jar's own (the jar's
     *       lines first); any other resource the jar already has stays the jar's.</li>
     *   <li>An explicit module declares the providers its parked service files add, and requires the modules the
     *       parked classes call into. A jar without a root descriptor whose patch holds no Vauban provider stays an
     *       automatic module: the JDK derives its {@code provides} from its service files.</li>
     *   <li>The manifest names the bricks in {@code Vidocq-Enriched-By}, and {@link Scanned#brickInputs()} join the
     *       inputs digest.</li>
     * </ul>
     *
     * @param mergedResources   the union of the bricks' {@code merge} lists
     * @param modulesOfPackages the modules, among the dependencies, that hold any of the given packages; a package
     *                          no module holds is left out
     */
    public static List<Path> enrichAll(Path buildDir, List<Scanned> scanned, List<String> providers,
                                       List<Path> closure, Set<String> mergedResources,
                                       Function<Set<String>, Set<String>> modulesOfPackages,
                                       Consumer<String> info, Consumer<String> warn) throws IOException {
        List<Path> enriched = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (Scanned dep : scanned) {
            Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
            if (!Files.isDirectory(patchDir)) {
                continue;
            }
            // A jar without a descriptor that a brick selected stays automatic, Vauban providers included (spec §4.4):
            // a synthesized descriptor must list every uses, and its detection misses ServiceLoader.load(Class,
            // ClassLoader), while an automatic module may use any service and gets its provides from its service
            // files. So its copy starts from the original jar, never from a vauban:modularize copy.
            boolean keepAutomatic = dep.selectedByBrick() && !hasDescriptor(dep.originalJar());
            Path source = keepAutomatic ? dep.originalJar() : ModularizedJars.resolve(buildDir, dep.originalJar());
            Set<String> packages = JpmsPatches.packagesOf(source);
            List<String> own = providers.stream().filter(p -> packages.contains(packageOf(p))).toList();
            // Only Vauban's providers need a descriptor on an automatic jar: the JDK derives an automatic module's
            // provides from its service files, which is all a brick's output asks.
            boolean automatic = !hasDescriptor(source);
            boolean synthesize = automatic && !keepAutomatic && (!own.isEmpty() || !providersIn(patchDir).isEmpty());
            BrickOutput brickOutput = new BrickOutput(dep.bricks(), mergedResources, modulesOfPackages,
                    keepAutomatic);
            Path out = root(buildDir).resolve(dep.originalJar().getFileName().toString());
            String inputs = inputs(source, patchDir, own, dep.coordinates(), synthesize ? closure : List.of(),
                    dep, mergedResources);
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
            write(source, moduleInfo, patchDir, own, dep.coordinates(), dep.originalJar(), out, inputs, brickOutput);
            current.add(out.getFileName().toString());
            enriched.add(dep.originalJar());
            info.accept("Enriched " + dep.originalJar().getFileName() + " with its generated code (" + own.size()
                    + " provider(s)" + (dep.bricks().isEmpty() ? "" : ", " + String.join(", ", dep.bricks()))
                    + ") → target/" + DIR_NAME + "/" + out.getFileName());
        }
        retainOnly(buildDir, current);
        return enriched;
    }

    /**
     * At packaging, after {@code vauban:modularize}: rebuilds the enriched copy of {@code dep} on the jar that goal
     * modularized, when there is one, so the module name and openness it was configured with are those shipped. On a
     * clean build {@code vidocq:generate} runs first and synthesizes a descriptor of its own; on a later build it
     * starts from the modularized jar already. Either way the package ends the same. A no-op when nothing changed.
     * {@code vidocq:package} does not call it for a copy {@link #keptAutomatic kept automatic}: that one ships under
     * its automatic module name.
     */
    public static void rebaseOnModularized(Path buildDir, Scanned dep, Consumer<String> info) throws IOException {
        rebaseOnModularized(buildDir, dep, Set.of(), packages -> Set.of(), info);
    }

    /**
     * {@link #rebaseOnModularized(Path, Scanned, Consumer)} for a jar bricks processed: the rebuilt copy merges and
     * declares their output as {@link #enrichAll(Path, List, List, List, Set, Function, Consumer, Consumer)} does.
     * A copy that names bricks in {@code Vidocq-Enriched-By} while {@code dep} names none is left as it is, since
     * rebuilding it without what merges and declares their output would lose it.
     */
    public static void rebaseOnModularized(Path buildDir, Scanned dep, Set<String> mergedResources,
                                           Function<Set<String>, Set<String>> modulesOfPackages,
                                           Consumer<String> info) throws IOException {
        Path out = root(buildDir).resolve(dep.originalJar().getFileName().toString());
        Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
        if (!Files.isRegularFile(out) || !Files.isDirectory(patchDir)
                || !ModularizedJars.isModularized(buildDir, dep.originalJar())) {
            return;
        }
        Path source = ModularizedJars.resolve(buildDir, dep.originalJar());
        if (!hasDescriptor(source)) {
            return;
        }
        if (dep.bricks().isEmpty() && manifestValue(out, ENRICHED_BY) != null) {
            info.accept("Kept the enriched copy of " + dep.originalJar().getFileName()
                    + " as vidocq:generate wrote it: the bricks that processed it are not known here");
            return;
        }
        List<String> providers = providersIn(patchDir);
        String inputs = inputs(source, patchDir, providers, dep.coordinates(), List.of(), dep, mergedResources);
        if (inputs.equals(inputsOf(out))) {
            return;
        }
        write(source, null, patchDir, providers, dep.coordinates(), dep.originalJar(), out, inputs,
                new BrickOutput(dep.bricks(), mergedResources, modulesOfPackages, false));
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
                inputs(source, patchDir, providers, coordinates, List.of(), null, Set.of()), BrickOutput.NONE);
    }

    /**
     * Writes {@code out} as {@link #write(Path, byte[], Path, List, String, Path, Path)} does, with the bricks'
     * output merged and declared. Without a descriptor to start from and without {@code providers}, the copy stays
     * an automatic module.
     */
    private static void write(Path source, byte[] moduleInfo, Path patchDir, List<String> providers,
                              String coordinates, Path originalJar, Path out, String inputs, BrickOutput bricks)
            throws IOException {
        Map<String, byte[]> patch = readTree(patchDir);
        List<byte[]> patchClasses = patch.entrySet().stream()
                .filter(e -> e.getKey().endsWith(CLASS_SUFFIX)).map(Map.Entry::getValue).toList();
        Set<String> requires = VaubanModuleReferences.of(patchClasses);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String> sourceFolders = new ArrayList<>();
        Manifest manifest = new Manifest();
        try (JarFile in = new JarFile(source.toFile(), false)) {
            if (in.getManifest() != null) {
                manifest = new Manifest(in.getManifest());
            }
            for (JarEntry entry : Collections.list(in.entries())) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    sourceFolders.add(name);
                }
                // A file, except the manifest and the signature files the enriched jar must not carry.
                if (!entry.isDirectory() && !name.equals(JarFile.MANIFEST_NAME) && !isSignature(name)) {
                    try (InputStream is = in.getInputStream(entry)) {
                        entries.put(name, is.readAllBytes());
                    }
                }
            }
        }
        // The root descriptor and those of a multi-release jar's versions: whichever the JDK reads must declare
        // the output. Without any, a synthesized descriptor goes at the root.
        List<String> descriptors = entries.keySet().stream().filter(EnrichedJars::isDescriptor).toList();
        if (descriptors.isEmpty() && moduleInfo != null) {
            entries.put(MODULE_INFO, moduleInfo);
            descriptors = List.of(MODULE_INFO);
        }
        if (descriptors.isEmpty() && !providers.isEmpty() && !bricks.keepAutomatic()) {
            throw new IllegalArgumentException(source.getFileName() + " has no module descriptor to enrich");
        }
        // service → the providers the parked service files add to the jar's: an explicit module declares them.
        Map<String, List<String>> addedProviders = new TreeMap<>();
        for (var e : patch.entrySet()) {
            String name = e.getKey();
            byte[] jars = entries.get(name);
            List<String> added = List.of();
            if (jars == null) {
                entries.put(name, e.getValue());
                added = isServiceFile(name) ? lines(e.getValue()) : List.of();
            } else if (bricks.mergedResources().contains(name)) {
                List<String> own = lines(jars);
                Set<String> merged = new LinkedHashSet<>(own);
                merged.addAll(lines(e.getValue()));
                entries.put(name, (String.join("\n", merged) + "\n").getBytes(StandardCharsets.UTF_8));
                added = merged.stream().filter(line -> !own.contains(line)).toList();
            }
            if (!added.isEmpty() && isServiceFile(name) && !name.equals(SERVICE_FILE)) {
                addedProviders.put(name.substring(SERVICES_DIR.length()), added);
            }
        }
        if (!descriptors.isEmpty()) {
            // Only a copy a brick processed requires for the bricks: a Vauban-only copy stays as Vauban writes it.
            Set<String> referenced = bricks.bricks().isEmpty() ? Set.of()
                    : bricks.modulesOfPackages().apply(ModuleDescriptorAugmenter.referencedPackages(patchClasses));
            // A descriptor that lists its packages must list those of the added classes and providers too.
            Set<String> addedPackages = new TreeSet<>();
            patch.keySet().stream().filter(n -> n.endsWith(CLASS_SUFFIX) && !n.startsWith(META_INF))
                    .forEach(n -> addedPackages.add(n.lastIndexOf('/') < 0 ? ""
                            : n.substring(0, n.lastIndexOf('/')).replace('/', '.')));
            providers.forEach(p -> addedPackages.add(packageOf(p)));
            addedProviders.values().forEach(impls -> impls.forEach(p -> addedPackages.add(packageOf(p))));
            for (String name : descriptors) {
                byte[] descriptor = ModuleInfoRewriter.addComponentProvider(entries.get(name), providers, requires);
                Set<String> brickRequires = new TreeSet<>(referenced);
                brickRequires.remove("java.base");
                brickRequires.remove(ModuleDescriptorAugmenter.moduleName(descriptor));
                entries.put(name, ModuleDescriptorAugmenter.augment(descriptor, addedProviders, brickRequires,
                        addedPackages));
            }
        }
        if (!providers.isEmpty()) {
            Set<String> services = new LinkedHashSet<>();
            if (entries.containsKey(SERVICE_FILE)) {
                services.addAll(lines(entries.get(SERVICE_FILE)));
            }
            services.addAll(providers);
            entries.put(SERVICE_FILE, (String.join("\n", services) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        manifest.getEntries().clear();
        Attributes main = manifest.getMainAttributes();
        main.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue(ENRICHED_FROM, coordinates);
        main.putValue(ENRICHED_DIGEST, "sha256:" + sha256(originalJar));
        main.putValue(ENRICHED_INPUTS, inputs);
        if (!bricks.bricks().isEmpty()) {
            main.putValue(ENRICHED_BY, String.join(", ", bricks.bricks()));
        }
        if (bricks.keepAutomatic()) {
            main.putValue(AUTOMATIC, AUTOMATIC_BRICK_SELECTED);
        } else {
            main.remove(new Attributes.Name(AUTOMATIC));
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), out.getFileName().toString(), ".tmp");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
            // Every folder gets its own entry, the source jar's own ones first, then any other before its first
            // file, as the jar tool writes them.
            Set<String> folders = new HashSet<>();
            for (String folder : sourceFolders) {
                putFolders(jar, folder, folders);
            }
            for (var e : entries.entrySet()) {
                putFolders(jar, e.getKey(), folders);
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Writes an entry for each folder of {@code name} that {@code written} does not hold yet, outermost first, and
     * adds it there. The JDK finds a folder resource of a jar, such as the frontend files Vaadin's development mode
     * copies, only through that entry.
     */
    static void putFolders(ZipOutputStream out, String name, Set<String> written) throws IOException {
        for (int i = name.indexOf('/', 1); i >= 0; i = name.indexOf('/', i + 1)) {
            String folder = name.substring(0, i + 1);
            if (written.add(folder)) {
                out.putNextEntry(new ZipEntry(folder));
                out.closeEntry();
            }
        }
    }

    /** {@code META-INF/*.SF}, {@code *.RSA}, {@code *.DSA}, {@code *.EC}, {@code SIG-*}: a signature. */
    public static boolean isSignature(String name) {
        if (!name.startsWith(META_INF) || name.indexOf('/', META_INF.length()) >= 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    /**
     * {@code sha256:…} over what a copy is written from: the source jar, the parked classes, the providers, the
     * coordinates, when a descriptor is synthesized the names of the jars jdeps resolves against, and, for a jar
     * bricks processed, each brick, the lines {@link Scanned#brickInputs()} carry and the parked resources merged,
     * and the layout of the copy, so that a copy written with an older layout is rewritten.
     *
     * @param dep the scanned jar, or {@code null} for a direct {@code write}
     */
    private static String inputs(Path source, Path patchDir, List<String> providers, String coordinates,
                                 List<Path> closure, Scanned dep, Set<String> mergedResources) throws IOException {
        MessageDigest digest = sha256();
        Consumer<String> add = s -> digest.update((s + "\n").getBytes(StandardCharsets.UTF_8));
        add.accept("source " + sha256(source));
        // A copy written before folders had their own entries is rewritten once
        add.accept("layout folders");
        add.accept("coordinates " + coordinates);
        providers.stream().sorted().forEach(p -> add.accept("provider " + p));
        Map<String, byte[]> patch = readTree(patchDir);
        for (var e : patch.entrySet()) {
            add.accept("class " + e.getKey() + " " + HexFormat.of().formatHex(sha256().digest(e.getValue())));
        }
        closure.stream().map(p -> p.getFileName().toString()).sorted().forEach(n -> add.accept("closure " + n));
        if (dep != null) {
            dep.bricks().forEach(b -> add.accept("brick " + b));
            dep.brickInputs().forEach(add);
            if (dep.selectedByBrick() && !hasDescriptor(dep.originalJar())) {
                add.accept("automatic " + AUTOMATIC_BRICK_SELECTED); // a copy stamped before the marker is rewritten
            }
        }
        patch.keySet().stream().filter(mergedResources::contains).forEach(n -> add.accept("merge " + n));
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /** The inputs stamp of an existing copy, or {@code null} when there is none or it cannot be read. */
    private static String inputsOf(Path copy) {
        return manifestValue(copy, ENRICHED_INPUTS);
    }

    /** A main attribute of an existing copy, or {@code null} when there is none or it cannot be read. */
    private static String manifestValue(Path copy, String attribute) {
        if (!Files.isRegularFile(copy)) {
            return null;
        }
        try (JarFile in = new JarFile(copy.toFile(), false)) {
            return in.getManifest() == null ? null : in.getManifest().getMainAttributes().getValue(attribute);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code META-INF/services/<service>}, at that level only. */
    private static boolean isServiceFile(String name) {
        return name.startsWith(SERVICES_DIR) && name.length() > SERVICES_DIR.length()
                && name.indexOf('/', SERVICES_DIR.length()) < 0;
    }

    /** The lines of a text resource, stripped, without blank lines and {@code #} comments. */
    private static List<String> lines(byte[] content) {
        return new String(content, StandardCharsets.UTF_8).lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
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
                .map(name -> name.substring(0, name.length() - CLASS_SUFFIX.length()).replace('/', '.'))
                .sorted().toList();
    }

    /**
     * Whether the JDK reads {@code jar} as an explicit module: a descriptor at its root, or, in a multi-release jar,
     * under {@code META-INF/versions/<n>/}.
     */
    static boolean hasDescriptor(Path jar) throws IOException {
        try (JarFile in = new JarFile(jar.toFile(), false)) {
            return hasDescriptor(in);
        }
    }

    /** {@link #hasDescriptor(Path)} on an open jar. */
    static boolean hasDescriptor(JarFile in) throws IOException {
        if (in.getEntry(MODULE_INFO) != null) {
            return true;
        }
        Manifest manifest = in.getManifest();
        return manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"))
                && in.stream().anyMatch(e -> isDescriptor(e.getName()));
    }

    /** {@code module-info.class}, or {@code META-INF/versions/<n>/module-info.class}. */
    private static boolean isDescriptor(String name) {
        return name.equals(MODULE_INFO) || VERSIONED_DESCRIPTOR.matcher(name).matches();
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
