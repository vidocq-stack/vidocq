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
package io.vidocq.runtime.maven.modularize;

import io.vidocq.runtime.maven.ModularizedJars;
import io.vidocq.vauban.maven.module.ModuleAnalysisResult;
import io.vidocq.vauban.maven.module.ModuleAnalyzer;
import io.vidocq.vauban.maven.module.SplitPackage;
import org.moditect.commands.AddModuleInfo;
import org.moditect.commands.GenerateModuleInfo;
import org.moditect.model.DependencePattern;
import org.moditect.model.DependencyDescriptor;
import org.moditect.model.GeneratedModuleInfo;
import org.moditect.model.PackageNamePattern;
import org.moditect.spi.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Patches non-modular dependency jars with a generated {@code module-info}
 * (ModiTect: {@code jdeps}-derived requires, all packages exported, META-INF/services
 * promoted to {@code provides}). Output goes to {@code target/vidocq-modularized/}
 * under the ORIGINAL file name so {@link ModularizedJars#resolve} substitutes it.
 */
public final class Modularizer {

    /** Which automatic jars are patched. */
    public enum Mode {
        /** Only jars whose automatic name is derived from the file name (default). */
        DERIVED,
        /** Every automatic jar, including those with an {@code Automatic-Module-Name} (jlink needs this). */
        ALL_AUTOMATIC
    }

    /** Tuning knobs, all mirrored 1:1 by the mojo parameters. */
    public record Options(Mode mode, Set<String> includeArtifactIds, Set<String> excludeArtifactIds,
                          Map<String, String> moduleNames, boolean openModules, String release) {}

    /** What was patched, what was left alone, and a human-readable report. */
    public record Result(List<Path> patched, List<Path> skipped, String report) {}

    /** {@code <artifactId>-<version>.jar} → {@code <version>}. */
    private static final Pattern VERSION_IN_FILE_NAME = Pattern.compile("-(\\d[^/]*?)\\.jar$");

    /** Name of the human-readable summary dropped next to the patched jars. */
    private static final String REPORT_FILE_NAME = "report.txt";

    private Modularizer() {}

    /**
     * Patches the automatic jars of {@code closure} selected by {@code options}.
     *
     * <p>The output directory is emptied first, so a jar patched by an earlier run with
     * different includes/excludes can never linger and be silently substituted.
     *
     * @param closure         every jar of the dependency closure, patched or not: jdeps needs
     *                        the whole set to resolve {@code requires}
     * @param artifactIdByJar artifact id per jar, used by the include/exclude filters and the
     *                        {@code moduleNames} overrides; the file name is used as a fallback
     * @param buildDir        the project {@code target/} directory
     * @param log             receives one line per patched jar plus ModiTect's own output
     * @throws IllegalStateException if two automatic jars of the closure share a package
     * @throws IOException           if a jar cannot be read, or jdeps/ModiTect fails on one
     */
    public static Result run(List<Path> closure, Map<Path, String> artifactIdByJar, Path buildDir,
                             Options options, Consumer<String> log) throws IOException {
        // 1. start from a clean output directory: a jar left over from a previous run with
        //    other includes/excludes would still be substituted by ModularizedJars.resolve.
        Path outDir = ModularizedJars.root(buildDir);
        Files.createDirectories(outDir);
        deleteFilesIn(outDir);

        // 2. classify
        List<JarModuleInfo> infos = new ArrayList<>();
        for (Path jar : closure) {
            infos.add(JarModuleClassifier.classify(jar));
        }

        // 3. select
        List<JarModuleInfo> selected = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        for (JarModuleInfo info : infos) {
            String artifactId = artifactIdOf(artifactIdByJar, info.jar());
            boolean wanted = switch (info.kind()) {
                case EXPLICIT -> false;
                case AUTOMATIC_DERIVED -> true;
                case AUTOMATIC_NAMED -> options.mode() == Mode.ALL_AUTOMATIC;
            };
            // Both filters only ever restrict: an explicit module named in <includes> stays
            // untouched, patching it would overwrite a descriptor its author wrote by hand.
            if (!options.includeArtifactIds().isEmpty()) {
                wanted &= options.includeArtifactIds().contains(artifactId);
            }
            if (options.excludeArtifactIds().contains(artifactId)) {
                wanted = false;
            }
            if (wanted) {
                selected.add(info);
            } else {
                skipped.add(info.jar());
            }
        }

        // 4. split-package guard over the automatic part of the closure
        assertNoSplitPackage(infos);

        // 5. patch
        // ModiTect recreates <dir>/<moduleName> under both of these, so neither may be outDir:
        // the modularized directory is handed to the JVM as a module path and must hold jars only.
        Path scratch = buildDir.resolve("vidocq-modularize-work");
        Path workDir = Files.createDirectories(scratch.resolve("work"));
        Path genDir = Files.createDirectories(scratch.resolve("generated"));

        Set<DependencyDescriptor> deps = new LinkedHashSet<>();
        for (Path jar : closure) {
            deps.add(new DependencyDescriptor(jar, false, null));
        }
        // Built once over the whole closure: a service-lookup helper is regularly in another jar
        // than the code that names the service type (langchain4j-core's ServiceHelper, called
        // from langchain4j), so the call graph has to be closure-wide to be seen whole.
        ServiceUsesScanner usesScanner = ServiceUsesScanner.over(closure);
        StringBuilder report = new StringBuilder("# vidocq:modularize report\n");
        List<Path> patched = new ArrayList<>();
        Log mlog = new ConsumerLog(log);
        for (JarModuleInfo info : selected) {
            String artifactId = artifactIdOf(artifactIdByJar, info.jar());
            String name = options.moduleNames().getOrDefault(artifactId, info.moduleName());
            String source;
            try {
                GeneratedModuleInfo gen = new GenerateModuleInfo(
                        info.jar(), name, options.openModules(), deps,
                        // `parsePatterns` (plural) is the parser for the ";"-terminated ModiTect
                        // configuration form; `parsePattern` would take the ";" for pattern text
                        // and silently match nothing, dropping every exports directive.
                        PackageNamePattern.parsePatterns("*;"),          // export everything
                        // "The opens table for an open module must be 0 length": an `open module`
                        // already opens everything, and a redundant `opens` makes the descriptor
                        // unreadable. Only a closed module gets the open-everything patterns.
                        options.openModules() ? List.of() : PackageNamePattern.parsePatterns("*;"),
                        DependencePattern.parsePatterns("*;"),           // keep every jdeps requires
                        workDir, genDir, Set.of(), Set.of(), Set.of(),
                        // addServiceUses stays false: ModiTect's own scanner runs on a shaded ASM
                        // that rejects any class file newer than it knows ("Unsupported class file
                        // major version 69"), which would break modularize on every jar built with
                        // a recent JDK. The `uses` directives are appended below instead, scanned
                        // with the JDK Class-File API — see ServiceUsesScanner.
                        false,
                        List.of("--multi-release", options.release(), "--ignore-missing-deps"),
                        mlog).run();
                // An automatic module may consume any service; an explicit one may only consume
                // what it declares. Without these directives the very jar we just promoted fails
                // its own lookup with "module … does not declare 'uses'".
                source = withUses(Files.readString(gen.getPath()), usesScanner.scan(info.jar()));
                // `base` keeps module-info.class at the jar root. Passing a JVM version instead
                // would hide the descriptor under META-INF/versions/<n> and stamp the jar
                // `Multi-Release: true` — a gratuitous change of shape for a jar that has none.
                // `release` stays what it is: the JDK level jdeps analyses against.
                new AddModuleInfo(source, null, versionOf(info.jar()), info.jar(), outDir,
                        "base", true, Instant.EPOCH).run();
            } catch (RuntimeException e) {
                // ModiTect reports every jdeps and compilation failure as an unchecked
                // exception naming nothing; `run` promises IOException, so name the jar.
                throw new IOException("vidocq:modularize — jdeps/ModiTect failed for "
                        + info.jar().getFileName() + ": " + e.getMessage(), e);
            }
            Path out = outDir.resolve(info.jar().getFileName().toString());
            if (!Files.isRegularFile(out)) {
                throw new IOException("ModiTect did not produce " + out);
            }
            patched.add(out);
            report.append("\n## ").append(info.jar().getFileName()).append(" → module ").append(name)
                    .append(" (").append(info.kind()).append(")\n").append(source).append('\n');
            log.accept("modularized " + info.jar().getFileName() + " as " + name);
        }
        for (Path s : skipped) {
            report.append("\nskipped: ").append(s.getFileName()).append('\n');
        }
        Files.writeString(outDir.resolve(REPORT_FILE_NAME), report.toString());
        return new Result(patched, skipped, report.toString());
    }

    /**
     * Inserts one {@code uses <service>;} line per scanned service into the body of the
     * ModiTect-generated descriptor source, just before its closing brace.
     *
     * @return {@code source} unchanged when there is nothing to add
     */
    private static String withUses(String source, Set<String> services) {
        if (services.isEmpty()) {
            return source;
        }
        int close = source.lastIndexOf('}');
        if (close < 0) {
            // Not a body we recognise; a malformed descriptor is ModiTect's to report, not ours.
            return source;
        }
        StringBuilder sb = new StringBuilder(source.substring(0, close));
        for (String service : services) {
            sb.append("    uses ").append(service).append(";\n");
        }
        return sb.append(source.substring(close)).toString();
    }

    /**
     * Two automatic jars sharing a package cannot both become named modules, and the
     * module system would reject them side by side anyway — fail early with the names.
     */
    private static void assertNoSplitPackage(List<JarModuleInfo> infos) throws IOException {
        List<ModuleAnalysisResult> analyses = new ArrayList<>();
        for (JarModuleInfo info : infos) {
            if (info.isAutomatic()) {
                analyses.add(ModuleAnalyzer.analyze(info.jar()));
            }
        }
        List<SplitPackage> splits = ModuleAnalyzer.detectSplitPackages(analyses);
        if (splits.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("vidocq:modularize — split package(s) between dependency jars, "
                + "the module system cannot host them together:\n");
        for (SplitPackage sp : splits) {
            sb.append("  ").append(sp.packageName()).append(" in ")
                    .append(String.join(", ", sp.jarFiles())).append('\n');
        }
        // Not something <excludes> can silence: the guard covers the whole automatic closure,
        // and leaving one side unpatched would not make the packages any less split.
        sb.append("Remove one side from the dependency graph (Maven <exclusions>) "
                + "or wait for an upstream fix.");
        throw new IllegalStateException(sb.toString());
    }

    /** The configured artifact id for {@code jar}, falling back to its file name. */
    private static String artifactIdOf(Map<Path, String> artifactIdByJar, Path jar) {
        return artifactIdByJar.getOrDefault(jar, jar.getFileName().toString());
    }

    /** Empties {@code dir} of its regular files (patched jars and the previous report). */
    private static void deleteFilesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }
    }

    /** The module version to stamp, read off the file name; {@code null} when there is none. */
    private static String versionOf(Path jar) {
        Matcher m = VERSION_IN_FILE_NAME.matcher(jar.getFileName().toString());
        return m.find() ? m.group(1) : null;
    }

    /** Bridges ModiTect logging onto the mojo log. */
    private record ConsumerLog(Consumer<String> sink) implements Log {
        @Override public void debug(CharSequence m) {}
        @Override public void info(CharSequence m) { sink.accept(String.valueOf(m)); }
        @Override public void warn(CharSequence m) { sink.accept("WARN " + m); }
        @Override public void error(CharSequence m) { sink.accept("ERROR " + m); }
    }
}
