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
package io.vidocq.runtime.cli.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves and layers configuration profiles for {@code vidocq dev}.
 *
 * <p>A profile {@code <name>} layers {@code vidocq-<name>.properties} on top of the
 * base {@code vidocq.properties} found in the project directory: keys defined in the
 * profile file override the base ones. The merged map is then applied as JVM system
 * properties, which are the highest-precedence Vidocq config source (ordinal 400),
 * so the values take effect through the normal runtime config pipeline.
 *
 * <p>This class is pure: {@link #merge} performs no I/O and is fully unit-testable.
 */
public final class Profiles {

    private static final String BASE_FILE = "vidocq.properties";

    private Profiles() {}

    /** Name of the profile file for {@code profile}, e.g. {@code vidocq-dev.properties}. */
    public static String profileFileName(String profile) {
        return "vidocq-" + profile + ".properties";
    }

    /**
     * Layers {@code profile} over {@code base}, returning a new ordered map where profile
     * entries win. Neither input is mutated.
     */
    public static Map<String, String> merge(Map<String, String> base, Map<String, String> profile) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(profile);
        return merged;
    }

    /**
     * The config files this profile would layer, base first, including only those that
     * exist on disk under {@code projectDir}.
     */
    public static List<Path> sourceFiles(Path projectDir, String profile) {
        List<Path> files = new ArrayList<>();
        Path base = projectDir.resolve(BASE_FILE);
        if (Files.isRegularFile(base)) {
            files.add(base);
        }
        if (profile != null && !profile.isBlank()) {
            Path profileFile = projectDir.resolve(profileFileName(profile));
            if (Files.isRegularFile(profileFile)) {
                files.add(profileFile);
            }
        }
        return List.copyOf(files);
    }

    /**
     * Reads every file in {@code files} in order and merges them (later files win),
     * returning the effective configuration map.
     */
    public static Map<String, String> load(List<Path> files) {
        Map<String, String> effective = new LinkedHashMap<>();
        for (Path file : files) {
            effective.putAll(read(file));
        }
        return effective;
    }

    private static Map<String, String> read(Path file) {
        var props = new Properties();
        try {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name, props.getProperty(name));
        }
        return map;
    }
}
