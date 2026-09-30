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

import org.apache.maven.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The dev console for {@code vidocq:dev} (Vidocq/vidocq#143), from this plugin's own dependencies, as
 * {@link DevServicesExtensionJar} does for the dev services: the console and its SPI, each unless the project already
 * has it. The console serves its page over Chappe: without the project's HTTP server extension, nothing is added, so
 * that a CLI application never starts a listener because it runs under {@code vidocq:dev}.
 */
final class DevConsoleJars {

    static final String CHAPPE_KEY =
            "io.vidocq.runtime.extensions.essentials:vidocq-runtime-chappe-webserver-extension";
    static final String CONSOLE_KEY = "io.vidocq.runtime.extensions.essentials:vidocq-runtime-devconsole-extension";
    static final String SPI_KEY = "io.vidocq.runtime:vidocq-runtime-devconsole-spi";

    private DevConsoleJars() {}

    /**
     * The version the dev console and its SPI are resolved at: the application's Vidocq runtime, read from its
     * Chappe extension (the console needs it, so it is there), as a base version ({@code 0.3.0-SNAPSHOT} for a
     * timestamped snapshot); {@code fallback}, the plugin's own version, without it (#148).
     */
    static String runtimeVersion(Collection<Artifact> projectArtifacts, String fallback) {
        for (Artifact artifact : projectArtifacts) {
            if (CHAPPE_KEY.equals(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                return artifact.getBaseVersion();
            }
        }
        return fallback;
    }

    /** The warning when the application's runtime is not this plugin's version, or {@code null} when it is. */
    static String versionWarning(String runtimeVersion, String pluginVersion) {
        if (runtimeVersion.equals(pluginVersion)) {
            return null;
        }
        return "Dev tools: the application runs Vidocq " + runtimeVersion + " and vidocq-runtime-maven-plugin is "
                + pluginVersion + "; the dev console follows the application (" + runtimeVersion
                + "). Align both versions if the console misbehaves.";
    }

    /**
     * Where the console is looked for: the project's repositories, then the plugin repositories — a snapshot
     * repository may be declared only as a plugin repository — each repository (by id) once (#148).
     */
    static List<RemoteRepository> repositories(List<RemoteRepository> project, List<RemoteRepository> plugin) {
        Map<String, RemoteRepository> byId = new LinkedHashMap<>();
        for (List<RemoteRepository> list : java.util.Arrays.asList(project, plugin)) {
            if (list != null) {
                for (RemoteRepository repository : list) {
                    byId.putIfAbsent(repository.getId(), repository);
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /** Whether {@code projectArtifacts} already has the Chappe HTTP server extension (Vidocq/vidocq#143). */
    static boolean needsConsole(Collection<Artifact> projectArtifacts) {
        for (Artifact artifact : projectArtifacts) {
            if (CHAPPE_KEY.equals(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The console keys that are neither among {@code projectArtifacts} nor in {@code alreadyOnPath}: the only ones to
     * resolve, so that a project that declares the console never needs a repository for it.
     */
    static List<String> missingKeys(Collection<Artifact> projectArtifacts, Set<String> alreadyOnPath) {
        Set<String> onPath = new HashSet<>(alreadyOnPath);
        for (Artifact artifact : projectArtifacts) {
            onPath.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
        }
        List<String> missing = new ArrayList<>();
        for (String key : List.of(CONSOLE_KEY, SPI_KEY)) {
            if (!onPath.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** As {@link #resolve(Map, Collection, Consumer, Set)}, discarding which keys were actually added. */
    static List<Path> resolve(Map<String, Artifact> pluginArtifacts, Collection<Artifact> projectArtifacts,
            Consumer<String> info) {
        return resolve(pluginArtifacts, projectArtifacts, info, new HashSet<>());
    }

    /**
     * The console and its SPI, unless the project already has either or lacks Chappe. {@code addedKeys} holds, on
     * entry, the keys an earlier step already put on the path (the dev services bring the SPI too), which are not
     * added again; it collects the {@code groupId:artifactId} of every jar actually added, so the caller can seed
     * {@link DevModules#collect} with them: a companion whose own transitive dependencies include the SPI must not
     * add it a second time (Vidocq/vidocq#143, spec §5.3 "never twice").
     */
    static List<Path> resolve(Map<String, Artifact> pluginArtifacts, Collection<Artifact> projectArtifacts,
            Consumer<String> info, Set<String> addedKeys) {
        Set<String> onPath = new HashSet<>(addedKeys);
        for (Artifact artifact : projectArtifacts) {
            onPath.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
        }
        if (!onPath.contains(CHAPPE_KEY)) {
            info.accept("Dev tools: no dev console, it needs vidocq-runtime-chappe-webserver-extension");
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        for (String key : List.of(CONSOLE_KEY, SPI_KEY)) {
            if (onPath.contains(key)) {
                continue;
            }
            Artifact artifact = pluginArtifacts == null ? null : pluginArtifacts.get(key);
            if (artifact == null || artifact.getFile() == null) {
                throw new IllegalStateException("The vidocq plugin lacks " + key + ": a broken plugin installation");
            }
            jars.add(artifact.getFile().toPath());
            addedKeys.add(key);
        }
        return jars;
    }
}
