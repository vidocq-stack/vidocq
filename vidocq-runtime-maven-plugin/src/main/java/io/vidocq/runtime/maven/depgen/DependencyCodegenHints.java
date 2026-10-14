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
package io.vidocq.runtime.maven.depgen;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * What a brick declares in {@value #PATH} so the build can tell which dependency jars it must run its code generation
 * on: the resources or class-file contents that make a jar relevant, the marker entries of a jar already processed,
 * and the service indexes to merge.
 *
 * @param brick      the brick's name, in messages
 * @param resources  jar entries whose presence selects the jar
 * @param classBytes strings (UTF-8) whose presence in a class file selects the jar
 * @param markers    jar entries that mark the jar as already processed by the brick
 * @param merge      jar entries merged across the jars the brick processes
 */
public record DependencyCodegenHints(String brick, List<String> resources, List<String> classBytes,
                                     List<String> markers, List<String> merge) {

    /** Where a bundle declares its hints. */
    public static final String PATH = "META-INF/vidocq/dependency-codegen.properties";

    private static final Set<String> KEYS = Set.of("brick", "resources", "class-bytes", "markers", "merge");

    public DependencyCodegenHints {
        resources = List.copyOf(resources);
        classBytes = List.copyOf(classBytes);
        markers = List.copyOf(markers);
        merge = List.copyOf(merge);
    }

    /**
     * Parses a hints file.
     *
     * @param origin names the bundle in error messages
     * @throws IllegalArgumentException when the file is malformed
     */
    public static DependencyCodegenHints parse(Properties p, String origin) {
        for (String key : p.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException(origin + ": unknown key '" + key + "' in " + PATH
                        + " (allowed: brick, resources, class-bytes, markers, merge)");
            }
        }
        String brick = p.getProperty("brick");
        if (brick == null || brick.isBlank()) {
            throw new IllegalArgumentException(origin + ": missing 'brick' in " + PATH);
        }
        var resources = list(p.getProperty("resources"));
        var classBytes = list(p.getProperty("class-bytes"));
        if (resources.isEmpty() && classBytes.isEmpty()) {
            throw new IllegalArgumentException(origin + ": " + PATH
                    + " declares neither 'resources' nor 'class-bytes': nothing would select a jar");
        }
        return new DependencyCodegenHints(brick.strip(), resources, classBytes, list(p.getProperty("markers")),
                list(p.getProperty("merge")));
    }

    /** The hints file of a bundle jar or directory; empty when it has none. */
    public static Optional<DependencyCodegenHints> read(Path bundleJarOrDir) throws IOException {
        var props = new Properties();
        if (Files.isDirectory(bundleJarOrDir)) {
            Path file = bundleJarOrDir.resolve(PATH);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                props.load(in);
            }
        } else {
            try (var jar = new JarFile(bundleJarOrDir.toFile())) {
                var entry = jar.getJarEntry(PATH);
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    props.load(in);
                }
            }
        }
        return Optional.of(parse(props, bundleJarOrDir.getFileName().toString()));
    }

    private static List<String> list(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
