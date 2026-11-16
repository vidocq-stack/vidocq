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

import io.vidocq.vauban.maven.enhance.DependencyEnhancer;
import io.vidocq.vauban.maven.enhance.ModuleInfoRewriter;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Adds to the modules a jlink image links the client proxies that Vauban's class loader places at each start under
 * the application layer (Vidocq/vidocq#580).
 *
 * <p>A producer of a normal scope that returns a third-party class with a package-private or protected member needs
 * a client proxy inside that class's own package: no class of another package can override the member. The
 * annotation processor lists such a type in the placement manifest of the bean archive ({@value #MANIFEST}) and
 * ships the proxy as a resource. Under the application layer, Vauban's class loader defines that proxy in the
 * library's package. An image has one layer, the JVM's own, whose loader defines only the classes a module holds, so
 * the application did not start (BUG-20261116-06).
 *
 * <p>The build does the placement once, here: it adds the proxy and its provider to the staged copy of the library,
 * as {@code vauban:enhance-dependencies} does ({@link DependencyEnhancer}), then takes the type off the manifest of
 * the staged archives, where nothing is left to place at the start. Only the staged copies change. A type the build
 * cannot place stays listed: the container decides at the start, as it did before.
 */
final class StagedProxies {

    /** The placement manifest of a bean archive: one produced type per line, {@code #} starts a comment. */
    static final String MANIFEST = "META-INF/vauban/required-opens.list";

    /** Where a bean archive ships the proxies that Vauban's class loader places. */
    private static final String PLACED = "META-INF/vauban/placed/";

    private static final String PROXY_SUFFIX = "_ClientProxy.class";

    /** What Vauban generates in a package for the container to find the classes it generated there. */
    private static final String COMPONENTS = "_VaubanComponents.class";

    private static final String MODULE_INFO = "module-info.class";

    /** Where a multi-release jar keeps what replaces its entries from a Java release on. */
    private static final String VERSIONS = "META-INF/versions";

    /** The prefix {@link DependencyEnhancer} gives its messages. */
    private static final String ENHANCER_PREFIX = "enhance: ";

    /** A client proxy added to the staged jar {@code jar}, for the produced type {@code type}. */
    record Placed(String type, String jar) {}

    /** The proxies added, and why a listed type was left to the container. */
    record Outcome(List<Placed> placed, List<String> warnings) {}

    /** A staged library, its enhanced copy, and the types that copy holds a proxy of. */
    private record Enhanced(Path staged, Path copy, List<String> types) {}

    private StagedProxies() {}

    /**
     * Places the proxies of every type that a staged archive lists.
     *
     * @param coordinates the Maven coordinates of a staged jar, from its file name: an enhanced copy records them
     */
    static Outcome place(Path stage, UnaryOperator<String> coordinates) throws IOException {
        List<Path> jars = jars(stage);
        Map<Path, List<String>> listed = new LinkedHashMap<>();
        for (Path jar : jars) {
            List<String> types = listedTypes(jar);
            if (!types.isEmpty()) {
                listed.put(jar, types);
            }
        }
        if (listed.isEmpty()) {
            return new Outcome(List.of(), List.of());
        }
        List<String> warnings = new ArrayList<>();
        Map<Path, List<String>> byLibrary = byLibrary(jars, listed.values(), warnings);
        List<Placed> placed = byLibrary.isEmpty() ? List.of() : enhance(stage, jars, byLibrary, coordinates, warnings);
        Set<String> placedTypes = new LinkedHashSet<>();
        placed.forEach(proxy -> placedTypes.add(proxy.type()));
        if (!placedTypes.isEmpty()) {
            for (Path archive : listed.keySet()) {
                forget(archive, placedTypes);
            }
        }
        return new Outcome(List.copyOf(placed), List.copyOf(warnings));
    }

    private static List<Path> jars(Path stage) throws IOException {
        try (Stream<Path> files = Files.list(stage)) {
            return files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"))
                    .sorted().toList();
        }
    }

    /** The types the placement manifest of {@code jar} lists; none when the jar has no manifest. */
    private static List<String> listedTypes(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var manifest = archive.getEntry(MANIFEST);
            if (manifest == null) {
                return List.of();
            }
            try (var in = archive.getInputStream(manifest)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(StagedProxies::namesAType).toList();
            }
        }
    }

    private static boolean namesAType(String line) {
        return !line.isEmpty() && !line.startsWith("#");
    }

    /** The listed types under the staged jar that holds their class, when the build can add a proxy to it. */
    private static Map<Path, List<String>> byLibrary(List<Path> jars, Collection<List<String>> listed,
            List<String> warnings) throws IOException {
        Set<String> types = new LinkedHashSet<>();
        listed.forEach(types::addAll);
        Map<Path, List<String>> byLibrary = new LinkedHashMap<>();
        for (String type : types) {
            Path library = holder(jars, type.replace('.', '/') + ".class");
            if (library == null) {
                warnings.add(type + ": no jar of the image holds this class");
            } else if (holds(library, packagePath(type) + COMPONENTS)) {
                // The enhancer writes one provider per package, under the name Vauban's processor uses.
                warnings.add(type + ": " + library.getFileName() + " already holds generated Vauban components in"
                        + " this package");
            } else {
                byLibrary.computeIfAbsent(library, _ -> new ArrayList<>()).add(type);
            }
        }
        return byLibrary;
    }

    private static Path holder(List<Path> jars, String entry) throws IOException {
        for (Path jar : jars) {
            if (holds(jar, entry)) {
                return jar;
            }
        }
        return null;
    }

    private static boolean holds(Path jar, String entry) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            return archive.getEntry(entry) != null;
        }
    }

    /** The path of the package of {@code type}, with its trailing slash; empty for the unnamed package. */
    private static String packagePath(String type) {
        return type.substring(0, type.lastIndexOf('.') + 1).replace('.', '/');
    }

    /**
     * Replaces each library by an enhanced copy, written first in a directory of its own next to the stage: the
     * enhancer reads the staged jar while it writes the copy.
     */
    private static List<Placed> enhance(Path stage, List<Path> jars, Map<Path, List<String>> byLibrary,
            UnaryOperator<String> coordinates, List<String> warnings) throws IOException {
        Path work = Files.createTempDirectory(stage.toAbsolutePath().getParent(), stage.getFileName() + "-proxies");
        try {
            List<Placed> placed = new ArrayList<>();
            for (Enhanced enhanced : enhancedCopies(work, jars, byLibrary, coordinates, warnings)) {
                Files.move(enhanced.copy(), enhanced.staged(), StandardCopyOption.REPLACE_EXISTING);
                String jar = enhanced.staged().getFileName().toString();
                enhanced.types().forEach(type -> placed.add(new Placed(type, jar)));
            }
            return placed;
        } finally {
            delete(work);
        }
    }

    /**
     * Writes the enhanced copies. The classes are read through one loader over every staged jar, closed before any
     * jar is replaced: the staged jars are those of the whole image, so a produced class finds there the types the
     * image holds.
     */
    private static List<Enhanced> enhancedCopies(Path work, List<Path> jars, Map<Path, List<String>> byLibrary,
            UnaryOperator<String> coordinates, List<String> warnings) throws IOException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        List<Enhanced> enhanced = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            for (Map.Entry<Path, List<String>> library : byLibrary.entrySet()) {
                String name = library.getKey().getFileName().toString();
                enhancedCopy(work, library.getKey(), coordinates.apply(name), library.getValue(), loader, warnings)
                        .ifPresent(enhanced::add);
            }
        }
        return enhanced;
    }

    private static Optional<Enhanced> enhancedCopy(Path work, Path library, String coordinates, List<String> types,
            ClassLoader loader, List<String> warnings) throws IOException {
        List<String> said = new ArrayList<>();
        DependencyEnhancer.Result result;
        try {
            result = DependencyEnhancer.enhance(library, coordinates, work, types, loader, said);
        } catch (LinkageError e) {
            // A class of the library refers to a type the image does not hold, an optional dependency for instance:
            // reflection cannot read such a class, and neither could the container at the start.
            warnings.add(String.join(", ", types) + ": " + library.getFileName() + " cannot be read (" + e + ")");
            return Optional.empty();
        }
        said.stream().filter(message -> aboutATypeLeftOut(message, result.enhancedTypes()))
                .map(message -> message.startsWith(ENHANCER_PREFIX)
                        ? message.substring(ENHANCER_PREFIX.length()) : message)
                .forEach(warnings::add);
        if (result.enhancedJar() == null) {
            return Optional.empty();
        }
        provideFromVersionedDescriptors(result.enhancedJar(), result.providerFqns());
        return Optional.of(new Enhanced(library, result.enhancedJar(), result.enhancedTypes()));
    }

    /**
     * Whether a message of the enhancer is about a type it left out. About a type it enhanced, it only advises to
     * let Vauban's class loader place the proxy instead: an image has no such loader.
     */
    private static boolean aboutATypeLeftOut(String message, List<String> enhancedTypes) {
        return enhancedTypes.stream().noneMatch(type -> message.startsWith(ENHANCER_PREFIX + type + " "));
    }

    /**
     * Gives the versioned descriptors of a multi-release copy what the enhancer gave its root descriptor: the
     * provider of the generated components, and the read of Vauban's API that provider needs. The enhancer rewrites
     * the root descriptor only (Vauban BUG-20261116-03), and many libraries have none but a versioned one. The
     * rewrite does nothing to a descriptor that already has both, so this step goes, with no other change, once the
     * Vauban this build reads rewrites the versioned descriptors itself.
     */
    private static void provideFromVersionedDescriptors(Path enhancedJar, List<String> providers)
            throws IOException {
        try (FileSystem jar = FileSystems.newFileSystem(enhancedJar)) {
            Path versions = jar.getPath(VERSIONS);
            if (!Files.isDirectory(versions)) {
                return;
            }
            List<Path> descriptors;
            try (Stream<Path> files = Files.walk(versions, 2)) {
                descriptors = files.filter(file -> file.getFileName() != null
                        && MODULE_INFO.equals(file.getFileName().toString())).toList();
            }
            for (Path descriptor : descriptors) {
                Files.write(descriptor,
                        ModuleInfoRewriter.addComponentProvider(Files.readAllBytes(descriptor), providers));
            }
        }
    }

    private static void delete(Path work) throws IOException {
        try (Stream<Path> files = Files.list(work)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(work);
    }

    /**
     * Takes {@code placedTypes} off the placement manifest of the staged {@code archive}, and removes the proxies it
     * shipped for them: the image holds those proxies as classes of their library, and has nothing to place.
     */
    private static void forget(Path archive, Set<String> placedTypes) throws IOException {
        try (FileSystem jar = FileSystems.newFileSystem(archive)) {
            Path manifest = jar.getPath(MANIFEST);
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            List<String> kept = lines.stream().filter(line -> !placedTypes.contains(line.strip())).toList();
            if (kept.stream().noneMatch(line -> namesAType(line.strip()))) {
                Files.delete(manifest);
            } else if (kept.size() != lines.size()) {
                Files.write(manifest, kept, StandardCharsets.UTF_8);
            }
            for (String type : placedTypes) {
                String simpleName = type.substring(type.lastIndexOf('.') + 1);
                Files.deleteIfExists(jar.getPath(PLACED + packagePath(type) + simpleName + PROXY_SUFFIX));
            }
        }
    }
}
