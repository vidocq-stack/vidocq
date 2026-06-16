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
package io.vidocq.runtime.cli.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates the {@code vidocq.properties} file for a project. Pure path logic so
 * candidate resolution is unit-testable; the actual read/write lives in the
 * command runner.
 */
public final class ConfigFile {

    /** Candidate relative locations, in priority order. */
    static final List<String> CANDIDATES = List.of(
            "vidocq.properties",
            "src/main/resources/vidocq.properties");

    private ConfigFile() {}

    /**
     * The first existing candidate under {@code projectDir}, using the supplied
     * existence predicate (so tests can drive it without real files).
     */
    public static Optional<Path> locate(Path projectDir, java.util.function.Predicate<Path> exists) {
        for (String candidate : CANDIDATES) {
            Path path = projectDir.resolve(candidate);
            if (exists.test(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /**
     * Where a value should be written when no file exists yet: the scaffold
     * location when {@code src/main/resources} is present, otherwise the project
     * root. The {@code dirExists} predicate decides whether the resources dir is
     * present.
     */
    public static Path resolveTarget(Path projectDir, java.util.function.Predicate<Path> dirExists) {
        Path resources = projectDir.resolve("src/main/resources");
        if (dirExists.test(resources)) {
            return resources.resolve("vidocq.properties");
        }
        return projectDir.resolve("vidocq.properties");
    }
}
