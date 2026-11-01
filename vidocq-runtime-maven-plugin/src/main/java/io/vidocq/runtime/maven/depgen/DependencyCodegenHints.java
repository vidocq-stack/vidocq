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
 * @param wholeJar   generated entries that describe every class of the jar: the processors see only the hinted
 *                   classes, so these would be partial, and the pass drops them
 * @param application the classes the application's own compilation names for the brick: a jar holding one of them
 *                   is selected, and the processors get their names
 */
public record DependencyCodegenHints(String brick, List<String> resources, List<String> classBytes,
                                     List<String> markers, List<String> merge, List<String> wholeJar,
                                     ApplicationClasses application) {

    /**
     * Classes the application's compilation lists, in a resource of its output, for the brick to process in the
     * dependencies that hold them: Champollion's {@code META-INF/champollion/dependency-types}, the types of a
     * dependency the application's REST resources exchange.
     *
     * @param resource where the application's output lists them, one binary name per line; {@code null} for none
     * @param option   the processor option ({@code -Aoption=a.B,c.D}) that gets the names of those a jar holds;
     *                 {@code null} for none
     * @param names    the names read from the resource ({@link #withApplication}); empty until then
     */
    public record ApplicationClasses(String resource, String option, Set<String> names) {

        public static final ApplicationClasses NONE = new ApplicationClasses(null, null, Set.of());

        public ApplicationClasses {
            names = Set.copyOf(names);
        }
    }

    /** Where a bundle declares its hints. */
    public static final String PATH = "META-INF/vidocq/dependency-codegen.properties";

    private static final Set<String> KEYS = Set.of("brick", "resources", "class-bytes", "markers", "merge",
            "whole-jar", "application-classes", "application-classes-option");

    public DependencyCodegenHints {
        application = application == null ? ApplicationClasses.NONE : application;
        resources = List.copyOf(resources);
        classBytes = List.copyOf(classBytes);
        markers = List.copyOf(markers);
        merge = List.copyOf(merge);
        wholeJar = List.copyOf(wholeJar);
    }

    /** Hints without {@code whole-jar} entries. */
    public DependencyCodegenHints(String brick, List<String> resources, List<String> classBytes,
                                  List<String> markers, List<String> merge) {
        this(brick, resources, classBytes, markers, merge, List.of());
    }

    /** Hints without application classes. */
    public DependencyCodegenHints(String brick, List<String> resources, List<String> classBytes,
                                  List<String> markers, List<String> merge, List<String> wholeJar) {
        this(brick, resources, classBytes, markers, merge, wholeJar, ApplicationClasses.NONE);
    }

    /**
     * These hints with the application classes that {@code classesDir}, the application's compiled classes, lists
     * in {@link ApplicationClasses#resource}; these hints unchanged when they name no resource or it is absent.
     */
    public DependencyCodegenHints withApplication(Path classesDir) throws IOException {
        if (application.resource() == null || classesDir == null) {
            return this;
        }
        Path listed = classesDir.resolve(application.resource());
        if (!Files.isRegularFile(listed)) {
            return this;
        }
        Set<String> names = new java.util.TreeSet<>();
        for (String line : Files.readAllLines(listed, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                names.add(line.strip());
            }
        }
        return new DependencyCodegenHints(brick, resources, classBytes, markers, merge, wholeJar,
                new ApplicationClasses(application.resource(), application.option(), names));
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
                        + " (allowed: brick, resources, class-bytes, markers, merge, whole-jar, application-classes,"
                        + " application-classes-option)");
            }
        }
        String brick = p.getProperty("brick");
        if (brick == null || brick.isBlank()) {
            throw new IllegalArgumentException(origin + ": missing 'brick' in " + PATH);
        }
        var resources = list(p.getProperty("resources"));
        var classBytes = list(p.getProperty("class-bytes"));
        String applicationClasses = blankToNull(p.getProperty("application-classes"));
        if (resources.isEmpty() && classBytes.isEmpty() && applicationClasses == null) {
            throw new IllegalArgumentException(origin + ": " + PATH + " declares none of 'resources', 'class-bytes'"
                    + " and 'application-classes': nothing would select a jar");
        }
        String option = blankToNull(p.getProperty("application-classes-option"));
        return new DependencyCodegenHints(brick.strip(), resources, classBytes, list(p.getProperty("markers")),
                list(p.getProperty("merge")), list(p.getProperty("whole-jar")),
                new ApplicationClasses(applicationClasses, option, Set.of()));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> list(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
