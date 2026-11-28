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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * What the extensions, other jars and the application declare as start-time code, for the hot swap classifier: code
 * that ran once at start and does not run again until the layer is swapped (design §2.1). Each jar that brings a
 * technology ships {@value #FILE}, a properties file whose values are comma-separated binary names:
 *
 * <ul>
 *   <li>{@code long-lived-annotations}, {@code long-lived-types}: a class carrying one of these annotations, or
 *       extending or implementing one of these types, is long-lived: its constructors, field initializers and
 *       initializer methods are start-time;</li>
 *   <li>{@code start-annotations}: a method carrying one is start-time;</li>
 *   <li>{@code observer-annotations}, {@code start-events}: a method with a parameter carrying an observer annotation
 *       is start-time when that parameter's type, or a qualifier on it, is a start event;</li>
 *   <li>{@code start-callbacks}: {@code owner#name(descriptor)}, that method of a class extending or implementing the
 *       owner is start-time;</li>
 *   <li>{@code build-files}: paths in the class output that the build writes for its own use, a path ending with
 *       {@code /} naming a directory: like {@code META-INF/vidocq/build-info/}, a change to them alone reloads
 *       nothing (design §2, rule 3).</li>
 * </ul>
 *
 * <p>An unknown key or an entry that cannot be read is a warning naming the jar, and is skipped; it never fails the
 * session.
 */
record StartTimeDeclarations(Set<String> longLivedAnnotations, Set<String> longLivedTypes,
        Set<String> startAnnotations, Set<String> observerAnnotations, Set<String> startEvents,
        Set<Callback> startCallbacks, Set<String> buildFiles, List<String> sources) {

    /** Where a jar, or a class directory, declares its start-time code. */
    static final String FILE = "META-INF/vidocq/dev/start-time.properties";

    static final StartTimeDeclarations EMPTY =
            new StartTimeDeclarations(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of());

    private static final String LONG_LIVED_ANNOTATIONS = "long-lived-annotations";
    private static final String LONG_LIVED_TYPES = "long-lived-types";
    private static final String START_ANNOTATIONS = "start-annotations";
    private static final String OBSERVER_ANNOTATIONS = "observer-annotations";
    private static final String START_EVENTS = "start-events";
    private static final String START_CALLBACKS = "start-callbacks";
    private static final String BUILD_FILES = "build-files";
    private static final List<String> NAME_KEYS = List.of(LONG_LIVED_ANNOTATIONS, LONG_LIVED_TYPES,
            START_ANNOTATIONS, OBSERVER_ANNOTATIONS, START_EVENTS);

    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final Pattern CALLBACK = Pattern.compile("([^#]+)#(" + IDENTIFIER + ")(\\(.*)");

    /**
     * A start callback: the method {@code name} with {@code descriptor} of a class extending or implementing
     * {@code owner}.
     *
     * @param owner      binary name, dotted
     * @param descriptor method descriptor, such as {@code (Ljakarta/servlet/ServletConfig;)V}
     */
    record Callback(String owner, String name, String descriptor) {}

    StartTimeDeclarations {
        longLivedAnnotations = frozen(longLivedAnnotations);
        longLivedTypes = frozen(longLivedTypes);
        startAnnotations = frozen(startAnnotations);
        observerAnnotations = frozen(observerAnnotations);
        startEvents = frozen(startEvents);
        startCallbacks = frozen(startCallbacks);
        buildFiles = frozen(buildFiles);
        sources = List.copyOf(sources);
    }

    /**
     * Reads {@value #FILE} from each jar or class directory; one without it adds nothing.
     *
     * @param warn told of an unreadable jar, an unknown key or an entry it skipped, each naming its jar
     */
    static StartTimeDeclarations read(List<Path> jarsAndDirectories, Consumer<String> warn) {
        StartTimeDeclarations all = EMPTY;
        for (Path entry : jarsAndDirectories) {
            String source = Files.isDirectory(entry) ? entry.toString() : String.valueOf(entry.getFileName());
            try {
                Optional<String> content = content(entry);
                if (content.isPresent()) {
                    all = all.merge(parse(source, content.get(), warn));
                }
            } catch (IOException e) {
                warn.accept("Hot swap: cannot read " + source + " for its start-time declarations ("
                        + e.getMessage() + "); skipped");
            }
        }
        return all;
    }

    /** These declarations and {@code other}'s, with the sources of both in order. */
    StartTimeDeclarations merge(StartTimeDeclarations other) {
        var allSources = new ArrayList<>(sources);
        allSources.addAll(other.sources);
        return new StartTimeDeclarations(union(longLivedAnnotations, other.longLivedAnnotations),
                union(longLivedTypes, other.longLivedTypes), union(startAnnotations, other.startAnnotations),
                union(observerAnnotations, other.observerAnnotations), union(startEvents, other.startEvents),
                union(startCallbacks, other.startCallbacks), union(buildFiles, other.buildFiles), allSources);
    }

    /** {@code true} when no jar and no directory declared anything. */
    boolean isEmpty() {
        return sources.isEmpty();
    }

    private static Optional<String> content(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            Path file = entry.resolve(FILE);
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        }
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try (var zip = new ZipFile(entry.toFile())) {
            var file = zip.getEntry(FILE);
            if (file == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(file)) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static StartTimeDeclarations parse(String source, String content, Consumer<String> warn)
            throws IOException {
        var properties = new Properties();
        try (Reader reader = new StringReader(content)) {
            properties.load(reader);
        }
        Map<String, Set<String>> names = new LinkedHashMap<>();
        NAME_KEYS.forEach(key -> names.put(key, new LinkedHashSet<>()));
        Set<Callback> callbacks = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            List<String> values = values(properties.getProperty(key));
            if (names.containsKey(key)) {
                addNames(source, key, values, names.get(key), warn);
            } else if (key.equals(BUILD_FILES)) {
                addPaths(source, key, values, paths, warn);
            } else if (key.equals(START_CALLBACKS)) {
                for (String value : values) {
                    callback(value).ifPresentOrElse(callbacks::add, () -> warn.accept(
                            skipped(source, key, value, "expected owner#name(descriptor)")));
                }
            } else {
                warn.accept("Hot swap: " + source + ": unknown key '" + key + "' in " + FILE + ", skipped");
            }
        }
        return new StartTimeDeclarations(names.get(LONG_LIVED_ANNOTATIONS), names.get(LONG_LIVED_TYPES),
                names.get(START_ANNOTATIONS), names.get(OBSERVER_ANNOTATIONS), names.get(START_EVENTS), callbacks,
                paths, List.of(source));
    }

    private static void addPaths(String source, String key, List<String> values, Set<String> paths,
            Consumer<String> warn) {
        for (String value : values) {
            if (outputPath(value)) {
                paths.add(value);
            } else {
                warn.accept(skipped(source, key, value, "not a path in the class output"));
            }
        }
    }

    /** A relative path with {@code /} separators that stays in the class output. */
    static boolean outputPath(String value) {
        return !value.isEmpty() && !value.startsWith("/") && value.indexOf('\\') < 0
                && !("/" + value + "/").contains("/../");
    }

    private static void addNames(String source, String key, List<String> values, Set<String> names,
            Consumer<String> warn) {
        for (String value : values) {
            if (binaryName(value)) {
                names.add(value);
            } else {
                warn.accept(skipped(source, key, value, "not a binary class name"));
            }
        }
    }

    /** Dotted Java identifiers, checked without a repeated regex group (a stack overflow on a long input). */
    static boolean binaryName(String value) {
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0))
                    || !part.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Callback> callback(String value) {
        var matcher = CALLBACK.matcher(value);
        if (!matcher.matches() || !binaryName(matcher.group(1))) {
            return Optional.empty();
        }
        try {
            MethodTypeDesc.ofDescriptor(matcher.group(3));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        return Optional.of(new Callback(matcher.group(1), matcher.group(2), matcher.group(3)));
    }

    private static List<String> values(String raw) {
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",", -1)) {
            if (!value.isBlank()) {
                values.add(value.strip());
            }
        }
        return values;
    }

    private static String skipped(String source, String key, String value, String why) {
        return "Hot swap: " + source + ": '" + value + "' in " + key + " of " + FILE + " skipped (" + why + ")";
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        var all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static <T> Set<T> frozen(Set<T> set) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }
}
