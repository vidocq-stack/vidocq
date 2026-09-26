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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

    /** Whether {@code projectArtifacts} already has the Chappe HTTP server extension (Ruling 5). */
    static boolean needsConsole(Collection<Artifact> projectArtifacts) {
        for (Artifact artifact : projectArtifacts) {
            if (CHAPPE_KEY.equals(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    static List<Path> resolve(Map<String, Artifact> pluginArtifacts, Collection<Artifact> projectArtifacts,
            Consumer<String> info) {
        Set<String> onPath = new HashSet<>();
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
        }
        return jars;
    }
}
