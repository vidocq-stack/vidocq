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

import io.vidocq.runtime.maven.DevOnlyJars;
import org.apache.maven.artifact.Artifact;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * The {@code -dev} companions of the project's extensions (Vidocq/vidocq#143): each runtime extension with a live
 * panel names its companion in {@value #DESCRIPTOR}, same groupId and version. {@code vidocq:dev} adds each companion
 * with its dev-only runtime dependencies, never a module already on the path, and never a companion that would bring
 * runtime code the application does not have. A companion that cannot be resolved costs its live panel only.
 */
final class DevModules {

    static final String DESCRIPTOR = "META-INF/vidocq/dev-module";
    static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

    /** One resolved jar: {@code groupId:artifactId} and its file. */
    record Resolved(String key, Path file) {}

    /** Resolves {@code groupId:artifactId:version} and its runtime dependencies. */
    @FunctionalInterface
    interface Resolver {
        List<Resolved> resolve(String coordinates) throws Exception;
    }

    private DevModules() {}

    /** The companion artifactId {@code jar} names, if it has a readable descriptor. */
    static Optional<String> companionOf(Path jar, Consumer<String> warn) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(DESCRIPTOR);
            if (entry == null) {
                return Optional.empty();
            }
            List<String> ids = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    String id = line.strip();
                    if (!id.isEmpty() && !id.startsWith("#")) {
                        ids.add(id);
                    }
                }
            }
            if (ids.size() != 1 || !ARTIFACT_ID.matcher(ids.getFirst()).matches()) {
                warn.accept("Dev tools: " + DESCRIPTOR + " of " + jar.getFileName()
                        + " must hold one artifactId; that extension gets no dev module");
                return Optional.empty();
            }
            return Optional.of(ids.getFirst());
        } catch (IOException | SecurityException unreadable) {
            warn.accept("Dev tools: " + jar.getFileName() + " cannot be read; that extension gets no dev module");
            return Optional.empty();
        }
    }

    /** As {@link #collect(Collection, Resolver, Consumer, Consumer, Set)}, with nothing already on the path. */
    static List<Path> collect(Collection<Artifact> projectArtifacts, Resolver resolver, Consumer<String> warn,
            Consumer<String> info) {
        return collect(projectArtifacts, resolver, warn, info, Set.of());
    }

    /**
     * The jars to add to the child's module path, in the order of the project's artifacts. {@code alreadyOnPath} —
     * the keys ({@code groupId:artifactId}) of jars an earlier step already added, e.g. the dev console and its SPI
     * ({@link DevConsoleJars#resolve(java.util.Map, Collection, Consumer, Set)}) — is read only: a companion's own
     * transitive dependencies must not add any of them again (Vidocq/vidocq#143, spec §5.3 "never twice").
     */
    static List<Path> collect(Collection<Artifact> projectArtifacts, Resolver resolver, Consumer<String> warn,
            Consumer<String> info, Set<String> alreadyOnPath) {
        Set<String> onPath = new HashSet<>(alreadyOnPath);
        for (Artifact artifact : projectArtifacts) {
            onPath.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
        }
        List<Path> added = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Artifact artifact : projectArtifacts) {
            if (artifact.getFile() == null) {
                continue;
            }
            Optional<String> companion = companionOf(artifact.getFile().toPath(), warn);
            if (companion.isEmpty()) {
                continue;
            }
            String key = artifact.getGroupId() + ":" + companion.get();
            if (onPath.contains(key)) {
                continue;
            }
            String coordinates = key + ":" + artifact.getVersion();
            List<Resolved> resolved;
            try {
                resolved = resolver.resolve(coordinates);
            } catch (Exception unresolvable) {
                warn.accept("Dev tools: " + coordinates + " not found (" + unresolvable.getClass().getSimpleName()
                        + "); the " + artifact.getArtifactId() + " panel shows its boot facts only. Run the build"
                        + " once online, or mvn -U");
                continue;
            }
            List<Resolved> toAdd = new ArrayList<>();
            String foreign = null;
            for (Resolved jar : resolved) {
                if (onPath.contains(jar.key())) {
                    continue;
                }
                if (!DevOnlyJars.isDevOnly(jar.file())) {
                    foreign = jar.key();
                    break;
                }
                toAdd.add(jar);
            }
            if (foreign != null) {
                warn.accept("Dev tools: " + coordinates + " needs " + foreign
                        + ", which the application does not have; its panel shows its boot facts only");
                continue;
            }
            for (Resolved jar : toAdd) {
                onPath.add(jar.key());
                added.add(jar.file());
            }
            names.add(companion.get());
        }
        if (!names.isEmpty()) {
            info.accept("Dev tools: " + String.join(", ", names));
        }
        return added;
    }
}
