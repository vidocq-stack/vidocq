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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Keeps the classes that {@code vidocq:generate} produces for <em>scanned dependencies</em>
 * out of the consumer module, so the module path never sees a JPMS split package.
 *
 * <p>{@code VaubanGenerator} writes proxies/interceptors for a scanned bean into the
 * consumer's {@code target/classes}, in the <em>package of the bean</em> — a package owned
 * by the dependency's module. Packaged as-is, the consumer jar would contain packages of
 * another module and the boot layer would refuse to start
 * ({@code java.lang.module.ResolutionException}).</p>
 *
 * <p>The flow is:</p>
 * <ol>
 *   <li>{@code vidocq:generate} calls {@link #relocate} — cross-module classes move from
 *       {@code target/classes} to {@code target/vidocq-patches/<artifactId>/};</li>
 *   <li>packaging goals ({@code package}, {@code jlink}) call {@link #enrich} while copying
 *       or staging the dependency jars, producing enriched copies that carry their own
 *       generated classes (the packages already exist in the jar, so the module descriptor
 *       and its {@code ModulePackages} attribute stay correct);</li>
 *   <li>{@code vidocq:dev} calls {@link #patchModuleArgs} to wire the same classes into the
 *       child JVM with {@code --patch-module} (the dev module path uses the original jars).</li>
 * </ol>
 */
public final class JpmsPatches {

    /** Directory under {@code target/} where cross-module generated classes are parked. */
    public static final String DIR_NAME = "vidocq-patches";

    private JpmsPatches() {}

    /** Root of the patches tree for the given build directory ({@code target/vidocq-patches}). */
    public static Path root(Path buildDir) {
        return buildDir.resolve(DIR_NAME);
    }

    /** Patch directory for one dependency, existing or not ({@code target/vidocq-patches/<artifactId>}). */
    public static Path patchDirFor(Path buildDir, String artifactId) {
        return root(buildDir).resolve(artifactId);
    }

    /**
     * Moves every generated class whose package is owned by one of the scanned dependency
     * jars from {@code classesDir} to {@code target/vidocq-patches/<artifactId>/}. Classes
     * generated for the project's own beans (package not found in any scanned jar) stay in
     * place. Emptied package directories are pruned so the jar plugin does not record the
     * foreign packages in {@code ModulePackages}.
     *
     * @param classesDir        the consumer's {@code target/classes}
     * @param buildDir          the consumer's {@code target}
     * @param scannedByArtifactId scanned dependency jar → artifactId
     * @param generatedClasses  fully qualified names reported by the generator
     * @return artifactId → number of class files relocated (insertion-ordered, only non-zero entries)
     */
    public static Map<String, Integer> relocate(Path classesDir, Path buildDir,
            Map<Path, String> scannedByArtifactId, List<String> generatedClasses) throws IOException {

        Map<String, Set<String>> packagesByArtifactId = new LinkedHashMap<>();
        for (var e : scannedByArtifactId.entrySet()) {
            packagesByArtifactId.put(e.getValue(), packagesOf(e.getKey()));
        }

        Map<String, Integer> relocated = new LinkedHashMap<>();
        Set<Path> touchedPackageDirs = new HashSet<>();
        for (String fqcn : generatedClasses) {
            int dot = fqcn.lastIndexOf('.');
            String pkg = dot > 0 ? fqcn.substring(0, dot) : "";
            String owner = packagesByArtifactId.entrySet().stream()
                    .filter(e -> e.getValue().contains(pkg))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (owner == null) {
                continue; // project-owned package: the class legitimately stays in this module
            }
            Path pkgDir = classesDir.resolve(pkg.replace('.', '/'));
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }
            String simpleName = fqcn.substring(dot + 1);
            // The class itself plus any inner classes it may have spawned.
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(pkgDir,
                    p -> matchesClassFile(p.getFileName().toString(), simpleName))) {
                ds.forEach(files::add);
            }
            if (files.isEmpty()) {
                continue;
            }
            Path targetDir = patchDirFor(buildDir, owner).resolve(pkg.replace('.', '/'));
            Files.createDirectories(targetDir);
            for (Path f : files) {
                Files.move(f, targetDir.resolve(f.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            relocated.merge(owner, files.size(), Integer::sum);
            touchedPackageDirs.add(pkgDir);
        }

        for (Path dir : touchedPackageDirs) {
            pruneEmptyDirs(classesDir, dir);
        }
        return relocated;
    }

    /**
     * Writes a copy of {@code srcJar} to {@code outJar} with every class file found under
     * {@code patchDir} added. Entries already present in the source jar win (the patch never
     * overwrites shipped classes). The module descriptor is left untouched: the added classes
     * belong to packages the jar already contains, so {@code ModulePackages} remains exact.
     */
    public static void enrich(Path srcJar, Path patchDir, Path outJar) throws IOException {
        Set<String> present = new HashSet<>();
        Files.createDirectories(outJar.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(outJar.toAbsolutePath().getParent(), outJar.getFileName().toString(), ".tmp");
        try (var zin = new ZipInputStream(Files.newInputStream(srcJar));
             var zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                present.add(entry.getName());
                zout.putNextEntry(new ZipEntry(entry.getName()));
                zin.transferTo(zout);
                zout.closeEntry();
            }
            try (Stream<Path> walk = Files.walk(patchDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    String name = patchDir.relativize(file).toString().replace('\\', '/');
                    if (present.contains(name)) {
                        return;
                    }
                    try {
                        zout.putNextEntry(new ZipEntry(name));
                        Files.copy(file, zout);
                        zout.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Files.move(tmp, outJar, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * {@code --patch-module <module>=<patchDir>} argument pairs for every patched dependency,
     * resolving each module name from the dependency jar's descriptor. Flattened, ready to
     * append to a JVM command line. Empty when no patches exist.
     *
     * @param jarsByArtifactId dependency artifactId → jar path (typically from the Maven project)
     */
    public static List<String> patchModuleArgs(Path buildDir, Map<String, Path> jarsByArtifactId) throws IOException {
        Path rootDir = root(buildDir);
        if (!Files.isDirectory(rootDir)) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(rootDir, Files::isDirectory)) {
            for (Path patchDir : ds) {
                Path jar = jarsByArtifactId.get(patchDir.getFileName().toString());
                if (jar == null) {
                    continue;
                }
                var descriptors = ModuleFinder.of(jar).findAll();
                if (descriptors.isEmpty()) {
                    continue;
                }
                String moduleName = descriptors.iterator().next().descriptor().name();
                args.add("--patch-module");
                args.add(moduleName + "=" + patchDir.toAbsolutePath());
            }
        }
        return args;
    }

    /** Dot-separated packages containing at least one class file in the given jar or exploded directory. */
    static Set<String> packagesOf(Path jarOrDir) throws IOException {
        Set<String> packages = new HashSet<>();
        if (Files.isDirectory(jarOrDir)) {
            try (Stream<Path> walk = Files.walk(jarOrDir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".class"))
                        .forEach(p -> {
                            Path rel = jarOrDir.relativize(p).getParent();
                            packages.add(rel == null ? "" : rel.toString().replace('\\', '/').replace('/', '.'));
                        });
            }
            return packages;
        }
        try (JarFile jf = new JarFile(jarOrDir.toFile())) {
            jf.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                    .forEach(e -> {
                        int slash = e.getName().lastIndexOf('/');
                        packages.add(slash < 0 ? "" : e.getName().substring(0, slash).replace('/', '.'));
                    });
        }
        return packages;
    }

    private static boolean matchesClassFile(String fileName, String simpleName) {
        return fileName.equals(simpleName + ".class")
                || (fileName.startsWith(simpleName + "$") && fileName.endsWith(".class"));
    }

    /** Deletes {@code dir} and its now-empty ancestors, stopping at {@code stopAt} (exclusive). */
    private static void pruneEmptyDirs(Path stopAt, Path dir) throws IOException {
        Path current = dir;
        while (current != null && !current.equals(stopAt) && Files.isDirectory(current)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.delete(current);
            current = current.getParent();
        }
    }
}
