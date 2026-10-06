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

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the Vidocq extensions a project actually runs with — the ones it declares and
 * the ones they pull in transitively — by resolving its runtime classpath with Maven
 * and keeping every JAR whose module provides {@code io.vidocq.runtime.spi.VidocqExtension}.
 *
 * <p>The CLI's own {@code ServiceLoader} cannot answer this: the CLI ships no extension,
 * and the project's dependencies are not on its module path.</p>
 */
public final class ProjectExtensions {

    static final String SPI = "io.vidocq.runtime.spi.VidocqExtension";

    /**
     * An extension present in the project's runtime classpath.
     *
     * @param id       the catalog short id when known, otherwise the artifactId
     * @param artifact the resolved dependency
     * @param direct   whether the project's {@code pom.xml} declares it itself
     */
    public record Installed(String id, ResolvedArtifact artifact, boolean direct) {}

    /** Outcome of {@link #resolve(Path, String)}: the extensions, or why Maven failed. */
    public sealed interface Resolution {
        /** {@code cached}: served by {@link #fromCache} without running Maven. */
        record Resolved(List<Installed> extensions, boolean cached) implements Resolution {}

        /**
         * @param reason      one-line cause
         * @param mavenOutput the tail of Maven's output, empty when Maven did not run
         */
        record Failed(String reason, String mavenOutput) implements Resolution {}
    }

    private ProjectExtensions() {}

    /**
     * The extensions recorded in {@code cacheFile} for the project in {@code projectDir},
     * when {@link ExtensionCache#isValid} still holds — no Maven involved.
     */
    public static Optional<List<Installed>> fromCache(Path projectDir, Path cacheFile) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return Optional.empty();
            }
            String pomHash = ExtensionCache.hash(Files.readString(projectDir.resolve("pom.xml")));
            return ExtensionCache.read(Files.readString(cacheFile))
                    .filter(e -> ExtensionCache.isValid(e, pomHash, ExtensionCache::fingerprint))
                    .map(ExtensionCache.Entry::extensions);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * {@link #resolve(Path, String)}, then records the answer in {@code cacheFile} for
     * {@link #fromCache}. A cache that cannot be written only costs the next run a resolution.
     */
    public static Resolution resolveAndCache(Path projectDir, String mavenExecutable, Path cacheFile) {
        List<ResolvedArtifact> resolved = new ArrayList<>();
        Resolution resolution = resolve(projectDir, mavenExecutable, resolved);
        if (resolution instanceof Resolution.Resolved r) {
            try {
                String pomXml = Files.readString(projectDir.resolve("pom.xml"));
                List<ExtensionCache.Fingerprint> snapshots = new ArrayList<>();
                for (Path file : ExtensionCache.snapshotFiles(resolved, PomDependencies.parent(pomXml))) {
                    ExtensionCache.Fingerprint fp = ExtensionCache.fingerprint(file);
                    if (fp == null) {
                        return resolution; // cannot vouch for it later: do not cache
                    }
                    snapshots.add(fp);
                }
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, ExtensionCache.write(new ExtensionCache.Entry(
                        ExtensionCache.hash(pomXml), snapshots, r.extensions())));
            } catch (IOException | IllegalArgumentException e) {
                // best effort: the next run resolves again
            }
        }
        return resolution;
    }

    /**
     * Resolves the project in {@code projectDir} with Maven (slow: Maven boots and may
     * download artifacts) and returns the extensions on its runtime classpath.
     */
    public static Resolution resolve(Path projectDir, String mavenExecutable) {
        return resolve(projectDir, mavenExecutable, new ArrayList<>());
    }

    private static Resolution resolve(Path projectDir, String mavenExecutable,
                                      List<ResolvedArtifact> resolvedOut) {
        Path pom = projectDir.resolve("pom.xml");
        Path output = null;
        Path log = null;
        try {
            output = Files.createTempFile("vidocq-deps", ".txt");
            log = Files.createTempFile("vidocq-deps", ".log");
            Process process = new ProcessBuilder(DependencyList.command(mavenExecutable, output))
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            int exit = process.waitFor();
            if (exit != 0) {
                return new Resolution.Failed("Maven exited with code " + exit, lastLines(log));
            }
            List<ResolvedArtifact> resolved = DependencyList.parse(Files.readString(output));
            List<ExtensionCoordinate> declared = PomDependencies.parse(Files.readString(pom));
            resolvedOut.addAll(resolved);
            return new Resolution.Resolved(detect(resolved, declared), false);
        } catch (IOException | IllegalArgumentException e) {
            return new Resolution.Failed(String.valueOf(e.getMessage()), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resolution.Failed("interrupted", "");
        } finally {
            deleteQuietly(output);
            deleteQuietly(log);
        }
    }

    /**
     * Keeps the {@code resolved} artifacts whose JAR provides the extension SPI, in
     * resolution order. Pure apart from reading the JARs.
     */
    public static List<Installed> detect(List<ResolvedArtifact> resolved,
                                         List<ExtensionCoordinate> declared) {
        List<Installed> result = new ArrayList<>();
        for (ResolvedArtifact artifact : resolved) {
            if (providesExtension(artifact.file())) {
                result.add(new Installed(idOf(artifact), artifact,
                        declared.contains(artifact.coordinate())));
            }
        }
        return result;
    }

    /**
     * Whether the JAR's module — explicit {@code module-info} or, for an automatic
     * module, {@code META-INF/services} — provides the extension SPI.
     */
    public static boolean providesExtension(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try {
            Set<ModuleReference> modules = ModuleFinder.of(jar).findAll();
            return modules.stream()
                    .flatMap(ref -> ref.descriptor().provides().stream())
                    .map(ModuleDescriptor.Provides::service)
                    .anyMatch(SPI::equals);
        } catch (FindException | SecurityException e) {
            return false;
        }
    }

    private static String idOf(ResolvedArtifact artifact) {
        return KnownExtensions.catalog().stream()
                .filter(e -> e.coordinate().equals(artifact.coordinate()))
                .map(RegistryEntry::id)
                .findFirst()
                .orElse(artifact.artifactId());
    }

    private static String lastLines(Path log) {
        try {
            List<String> lines = Files.readAllLines(log);
            List<String> tail = lines.subList(Math.max(0, lines.size() - 15), lines.size());
            return String.join("\n", tail);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
