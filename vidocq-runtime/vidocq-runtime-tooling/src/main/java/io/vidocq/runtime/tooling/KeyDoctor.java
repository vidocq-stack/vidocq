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
package io.vidocq.runtime.tooling;

import io.vidocq.runtime.config.keys.Attribute;
import io.vidocq.runtime.config.keys.Fact;
import io.vidocq.runtime.config.keys.KeyCatalog;
import io.vidocq.runtime.config.keys.KeyDescriptions;
import io.vidocq.runtime.config.keys.KeyFiles;
import io.vidocq.runtime.config.keys.Problem;
import io.vidocq.runtime.config.keys.ValueProblem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The checks {@code vidocq doctor} and {@code vidocq:doctor} make with the descriptions of the configuration keys
 * the project runs with: what is wrong in those files, and what the values of {@code vidocq.properties} have
 * against them. Built like {@link ProjectDoctor}: {@link #read} touches the disk, {@link #check} does not.
 *
 * <p>Every finding is a warning: a description that is wrong, or a value it refuses, never fails the doctor.
 * Nothing is said of a key no file describes.
 */
public final class KeyDoctor {

    private static final String DESCRIPTIONS_CHECK = "Key descriptions";
    private static final String VALUES_CHECK = "Config values";
    private static final String RESOURCES = "src/main/resources";
    private static final String CLASSES = "target/classes";

    /** How many findings a check shows before it counts the rest. */
    private static final int SHOWN = 10;

    /** How much of a value a finding prints: a value can be long, or a secret. */
    private static final int VALUE_SHOWN = 40;

    private static final String DESCRIPTIONS_HINT = "A file of key descriptions is wrong: fix the line named when it is"
            + " the project's META-INF/vidocq/config-user-keys.properties; in a jar, it is its author's to fix."
            + " The format is in the reference documentation, \"Configuration key descriptions\".";

    private static final String UNREADABLE_HINT = "The file itself cannot be opened, so what it describes is not"
            + " known. A jar is damaged or was not downloaded whole: delete it from the local Maven repository and"
            + " build again, so that it is fetched again.";

    private static final String JARS_UNREAD = "the jars of the project could not be read";

    private static final String UNRESOLVED_HINT = "The dependencies of the project could not be resolved, see the"
            + " Extensions check: only the project's own files were read. Run the doctor again once they resolve.";

    private KeyDoctor() {}

    /**
     * What the project says of its configuration keys.
     *
     * @param config  the keys of the project's {@code vidocq.properties} and their values, as the runtime reads
     *                them; empty without such a file
     * @param catalog the descriptions the project runs with: its own first, then those of its class path, in order
     * @param files   how many files of key descriptions were found
     * @param own      the origins of the files of the project itself: there, a fact this reader does not know is
     *                 a mistake to report, where in a jar it may come from a later version
     * @param resolved whether the class path of the project is known. False when the tool could not resolve it:
     *                 only the files of the project itself were read then, and the checks say so
     */
    public record Facts(Map<String, String> config, KeyCatalog catalog, int files, Set<String> own,
                        boolean resolved) {

        public Facts {
            config = Map.copyOf(config);
            own = Set.copyOf(own);
        }
    }

    /**
     * Reads the project in {@code projectDir} and the descriptions of {@code classPath}, the jars and the
     * directories of classes it runs with. Never throws on what they hold.
     *
     * <p>The file the project writes is the one of its sources when it is there, the one of its classes
     * otherwise; the file its build generates is the one of its classes. An entry of the class path is read
     * once, and the classes of the project itself are not read again there.
     */
    public static Facts read(Path projectDir, List<Path> classPath) {
        return read(projectDir, classPath, true);
    }

    /**
     * {@link #read(Path, List)} with what a tool found of the extensions of the project: their class path, or,
     * when they could not be resolved, the files of the project alone, which {@link #check} then says.
     */
    public static Facts read(Path projectDir, DoctorFacts.Extensions extensions) {
        return read(projectDir, extensions.classPath(), extensions.error() == null);
    }

    private static Facts read(Path projectDir, List<Path> classPath, boolean resolved) {
        KeyCatalog.Jar sources = KeyFiles.of(projectDir.resolve(RESOURCES));
        KeyCatalog.Jar classes = KeyFiles.of(projectDir.resolve(CLASSES));
        KeyCatalog.Jar project = new KeyCatalog.Jar(
                found(sources.written()) ? sources.written() : classes.written(), classes.generated());
        List<KeyCatalog.Jar> jars = new ArrayList<>();
        jars.add(project);
        Set<Path> read = new HashSet<>();
        read.add(normal(projectDir.resolve(CLASSES)));
        for (Path entry : classPath) {
            if (read.add(normal(entry))) {
                jars.add(KeyFiles.of(entry));
            }
        }
        Set<String> own = new LinkedHashSet<>();
        files(project).map(KeyDescriptions::origin).forEach(own::add);
        int files = (int) jars.stream().flatMap(KeyDoctor::files).count();
        return new Facts(config(projectDir), KeyCatalog.of(jars), files, own, resolved);
    }

    private static Stream<KeyDescriptions> files(KeyCatalog.Jar jar) {
        return Stream.of(jar.written(), jar.generated()).filter(KeyDoctor::found);
    }

    private static boolean found(KeyDescriptions file) {
        return !file.equals(KeyDescriptions.none());
    }

    private static Path normal(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * The keys and the values of the project's {@code vidocq.properties}, read with {@link Properties} as the
     * runtime reads them: a value is checked as the application will get it, escapes undone.
     */
    private static Map<String, String> config(Path projectDir) {
        Optional<Path> file = ConfigFile.locate(projectDir, Files::isRegularFile);
        if (file.isEmpty()) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file.get())) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException _) {
            // A file the runtime cannot read either: nothing to check a value of.
            return Map.of();
        }
        Map<String, String> config = new TreeMap<>();
        properties.stringPropertyNames().forEach(key -> config.put(key, properties.getProperty(key)));
        return config;
    }

    /**
     * The two checks, {@code Key descriptions} then {@code Config values}, in display order; never a failure.
     * When the jars of the project could not be resolved, each check ends with a warning that says so, after
     * what the project's own files give.
     */
    public static List<Diagnostic> check(Facts facts) {
        List<Diagnostic> diagnostics = new ArrayList<>(descriptions(facts));
        diagnostics.addAll(values(facts));
        return diagnostics;
    }

    /**
     * What is wrong in the files, by file and line; when nothing is, how much they describe. A project whose jars
     * could not be resolved is told so, last: nothing read is not nothing described.
     */
    private static List<Diagnostic> descriptions(Facts facts) {
        if (facts.resolved()) {
            return filesRead(facts);
        }
        List<Diagnostic> diagnostics = new ArrayList<>(facts.files() == 0 ? List.of() : filesRead(facts));
        diagnostics.add(Diagnostic.warn(DESCRIPTIONS_CHECK, JARS_UNREAD + ": the keys they describe are not known",
                UNRESOLVED_HINT));
        return diagnostics;
    }

    /** What the files that were read say of themselves. */
    private static List<Diagnostic> filesRead(Facts facts) {
        if (facts.files() == 0) {
            return List.of(Diagnostic.ok(DESCRIPTIONS_CHECK, "no jar of the project describes its configuration keys"));
        }
        // A fact of a later format is a mistake in a file of the project, and expected in a jar.
        List<Problem> wrong = facts.catalog().problems().stream()
                .filter(problem -> problem.kind() == Problem.Kind.WRONG || facts.own().contains(problem.origin()))
                .sorted(Comparator.comparing(Problem::origin).thenComparingInt(Problem::line))
                .toList();
        if (!wrong.isEmpty()) {
            return shown(DESCRIPTIONS_CHECK, wrong.stream()
                    // Line 0 is the file itself, which could not be read: nothing in it is to correct.
                    .map(problem -> Diagnostic.warn(DESCRIPTIONS_CHECK, at(problem.origin(), problem.line()) + " — "
                            + problem.message(), problem.line() == 0 ? UNREADABLE_HINT : DESCRIPTIONS_HINT))
                    .toList());
        }
        int skipped = facts.catalog().problems().size();
        return List.of(Diagnostic.ok(DESCRIPTIONS_CHECK, counted(facts.files(), "file") + ", "
                + counted(facts.catalog().keys().size(), "key") + " described"
                + (skipped == 0 ? "" : ", " + counted(skipped, "fact") + " of a later format skipped")));
    }

    /** What the values of the described keys have against their descriptions, in the order of the keys. */
    private static List<Diagnostic> values(Facts facts) {
        List<Diagnostic> findings = new ArrayList<>();
        int described = 0;
        for (Map.Entry<String, String> entry : new TreeMap<>(facts.config()).entrySet()) {
            if (facts.catalog().describe(entry.getKey()).isPresent()) {
                described++;
            }
            for (ValueProblem problem : facts.catalog().check(entry.getKey(), entry.getValue())) {
                findings.add(Diagnostic.warn(VALUES_CHECK, said(problem), described(problem)));
            }
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!findings.isEmpty()) {
            diagnostics.addAll(shown(VALUES_CHECK, findings));
        } else if (described > 0) {
            diagnostics.add(Diagnostic.ok(VALUES_CHECK, counted(described, "described key") + ", every value fits"));
        } else if (facts.resolved()) {
            diagnostics.add(Diagnostic.ok(VALUES_CHECK, "no described key in vidocq.properties"));
        }
        if (!facts.resolved()) {
            // The keys the jars describe are not known: their values passed no check.
            diagnostics.add(Diagnostic.warn(VALUES_CHECK, "the values were not checked against the jars of the"
                    + " project, which could not be read", UNRESOLVED_HINT));
        }
        return diagnostics;
    }

    /** A finding on a value: the key and the start of its value, or the key alone when the key is the finding. */
    private static String said(ValueProblem problem) {
        if (problem.kind() == ValueProblem.Kind.DEPRECATED) {
            return problem.key() + " — " + problem.message()
                    + problem.description().doc().map(doc -> ". " + doc).orElse("");
        }
        String value = problem.value().strip();
        return problem.key() + "=" + (value.length() > VALUE_SHOWN ? value.substring(0, VALUE_SHOWN) + "…" : value)
                + " — " + problem.message();
    }

    /** Where the description that refuses the value is written. */
    private static String described(ValueProblem problem) {
        Attribute attribute = switch (problem.kind()) {
            case NOT_OF_TYPE -> Attribute.TYPE;
            case NOT_ALLOWED -> Attribute.VALUES;
            case DEPRECATED -> Attribute.DEPRECATED;
        };
        Optional<Fact> fact = problem.description().fact(attribute);
        return fact.map(at -> "vidocq.properties gives the value; the key is described in "
                + at(at.origin(), at.line())).orElse(null);
    }

    /** The first findings of a check, then one line that counts the rest. */
    private static List<Diagnostic> shown(String check, List<Diagnostic> findings) {
        if (findings.size() <= SHOWN) {
            return findings;
        }
        List<Diagnostic> shown = new ArrayList<>(findings.subList(0, SHOWN));
        shown.add(Diagnostic.warn(check, "… and " + (findings.size() - SHOWN) + " more", null));
        return shown;
    }

    private static String at(String origin, int line) {
        return line > 0 ? origin + ":" + line : origin;
    }

    private static String counted(int count, String what) {
        return count + " " + what + (count == 1 ? "" : "s");
    }
}
