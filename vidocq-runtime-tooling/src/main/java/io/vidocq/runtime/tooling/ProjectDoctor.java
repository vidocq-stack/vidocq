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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The health of a Vidocq project, read from its sources so it works before a compile: the codegen bundle of each
 * extension, the Vauban indexer, the {@code module-info.java} directives the extensions need, the Maven the wrapper
 * runs and the HTTP port (Vidocq/vidocq#339). {@code vidocq doctor} and {@code vidocq:doctor} both run it after the
 * environment checks of {@link Diagnostics}, and {@link #fix} repairs what it can: the edits of
 * {@code vidocq extension add}, made for the extensions already there. Where the plugin wires the codegen bundles
 * itself ({@link PomDependencies#codegenWiredByPlugin}), a missing path is not one to add.
 *
 * <p>It reads what the project declares, not the effective model: {@code vidocq:checkpom} and
 * {@code vidocq:check-module-info} keep checking the build itself, with what Maven resolves.</p>
 */
public final class ProjectDoctor {

    /** Declared first: {@link #PINNED_MAVEN_VERSION} reads it at class initialization. */
    private static final Pattern MAVEN_DISTRIBUTION = Pattern.compile("apache-maven-(\\d[^/]*?)-bin\\.zip");

    /** The Maven the wrapper of a created project runs, which Vidocq is built and tested with. */
    public static final String PINNED_MAVEN_VERSION = mavenVersion(ProjectScaffolder.wrapperProperties())
            .orElseThrow(() -> new IllegalStateException("the tooling's maven-wrapper.properties names no Maven"));

    /** The port Chappe's default listener takes when the configuration names none. */
    static final int DEFAULT_HTTP_PORT = 8080;

    private static final String CODEGEN_CHECK = "Codegen bundles";
    private static final String INDEXER_CHECK = "Vauban indexer";
    private static final String MODULE_CHECK = "Module directives";
    private static final String DEV_SERVICES_CHECK = "Dev services";
    private static final String MAVEN_CHECK = "Maven version";
    private static final String PORT_CHECK = "HTTP port";

    private static final String POM_XML = "pom.xml";
    private static final String WRAPPER_PROPERTIES = ".mvn/wrapper/maven-wrapper.properties";
    private static final String MODULE_INFO = "src/main/java/module-info.java";
    private static final String CLASSES = "target/classes";
    /** The comment over the {@code opens} written for reflection, shared with {@code vidocq:complete-module-info}. */
    public static final String REFLECTION_MARKER = "// added by vidocq: the runtime reaches these packages by reflection";
    private static final ExtensionCoordinate INDEXER =
            new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-core-codegen");
    private static final String PORT_KEY = "vidocq.http.port";
    private static final String LISTENER_PORT_KEY = "vidocq.chappe.listener.default.port";

    private ProjectDoctor() {}

    /**
     * What the checks read from a project.
     *
     * @param pom                the {@code pom.xml}
     * @param moduleInfo         {@code src/main/java/module-info.java}, or {@code null} when absent
     * @param applicationPackage the package the module opens to reflecting extensions, or {@code null}
     * @param mavenVersion       the Maven the project's wrapper runs, or {@code null} without a wrapper
     * @param httpPort           the port of the default HTTP listener
     * @param httpPortFree       whether that port could be bound when the facts were read
     * @param unopenedPackages   the packages of {@code target/classes} the runtime reaches by reflection and the module
     *                           does not open, with the reason, as {@code vidocq:check-module-info} reads them; {@code
     *                           null} before a compile
     */
    public record Facts(String pom, String moduleInfo, String applicationPackage, String mavenVersion, int httpPort,
                        boolean httpPortFree, Map<String, String> unopenedPackages) {}

    /**
     * The facts of the project in {@code projectDir}, {@code portFree} telling whether a port is free; {@code null}
     * when the directory holds no Vidocq project.
     */
    public static Facts read(Path projectDir, IntPredicate portFree) {
        Path pomFile = projectDir.resolve(POM_XML);
        try {
            if (!Files.isRegularFile(pomFile)) {
                return null;
            }
            String pom = Files.readString(pomFile);
            if (!pom.contains("io.vidocq.runtime")) {
                return null;
            }
            Path moduleInfoFile = projectDir.resolve(MODULE_INFO);
            String moduleInfo = Files.isRegularFile(moduleInfoFile) ? Files.readString(moduleInfoFile) : null;
            String pkg = moduleInfo == null ? null
                    : ExtensionEditor.applicationPackage(moduleInfo, moduleInfoFile.getParent());
            Path wrapper = wrapperProperties(projectDir);
            String maven = wrapper == null ? null : mavenVersion(Files.readString(wrapper)).orElse(null);
            int port = httpPort(projectDir);
            Map<String, String> unopened = moduleInfo == null ? null
                    : ReflectedPackages.unopened(projectDir.resolve(CLASSES), moduleInfo);
            return new Facts(pom, moduleInfo, pkg, maven, port, portFree.test(port), unopened);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read the project in " + projectDir, e);
        }
    }

    /** The checks of {@code facts}, in display order; none for {@code null} facts (no Vidocq project). */
    public static List<Diagnostic> check(Facts facts) {
        if (facts == null) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, ExtensionCoordinate> extensions = catalogExtensions(facts.pom());
        diagnostics.add(checkCodegen(facts.pom(), extensions));
        diagnostics.add(checkIndexer(facts.pom(), !extensions.isEmpty()));
        diagnostics.add(checkModuleDirectives(facts, extensions));
        diagnostics.add(checkDevServices(facts.pom(), extensions));
        if (facts.mavenVersion() != null) {
            diagnostics.add(checkMaven(facts.mavenVersion()));
        }
        diagnostics.add(checkPort(facts));
        return diagnostics;
    }

    // -------------------------------------------------------------------------
    // Checks
    // -------------------------------------------------------------------------

    private static Diagnostic checkCodegen(String pom, Map<String, ExtensionCoordinate> extensions) {
        List<ExtensionCoordinate> paths = PomDependencies.processorPaths(pom);
        boolean byPlugin = PomDependencies.codegenWiredByPlugin(pom);
        List<String> missing = new ArrayList<>();
        extensions.keySet().forEach(id -> KnownExtensions.codegenBundle(id)
                .filter(bundle -> !byPlugin && !paths.contains(bundle))
                .ifPresent(bundle -> missing.add(id + ": " + bundle.artifactId())));
        if (!missing.isEmpty()) {
            return Diagnostic.fail(CODEGEN_CHECK,
                    "missing from annotationProcessorPaths — " + String.join(", ", missing),
                    "Without its bundle an extension's compile-time processing does not run, and vidocq:checkpom"
                            + " fails the build.").asFixable();
        }
        List<String> orphans = orphanCodegens(paths, extensions).stream()
                .map(c -> c.artifactId() + " without its extension").toList();
        if (!orphans.isEmpty()) {
            return Diagnostic.warn(CODEGEN_CHECK, String.join(", ", orphans),
                    "A leftover of a removed extension: vidocq:checkpom fails the build on it.").asFixable();
        }
        long wired = extensions.keySet().stream().filter(id -> KnownExtensions.codegenBundle(id).isPresent()).count();
        if (wired == 0) {
            return Diagnostic.ok(CODEGEN_CHECK, "no extension of the project ships one");
        }
        return Diagnostic.ok(CODEGEN_CHECK, byPlugin
                ? wired + " wired by vidocq-runtime-maven-plugin"
                : wired + " wired, one per extension that ships one");
    }

    private static Diagnostic checkIndexer(String pom, boolean hasExtensions) {
        if (PomDependencies.codegenWiredByPlugin(pom)) {
            return Diagnostic.ok(INDEXER_CHECK, "wired by vidocq-runtime-maven-plugin");
        }
        if (PomDependencies.runtimeVersion(pom).isPresent()) {
            if (PomEditor.dropsInheritedProcessorPaths(pom)) {
                return Diagnostic.fail(INDEXER_CHECK,
                        "annotationProcessorPaths replaces the parent's, which hold the indexer",
                        "The application's beans get no generated factory: declare"
                                + " <annotationProcessorPaths combine.children=\"append\">.").asFixable();
            }
            return Diagnostic.ok(INDEXER_CHECK, "wired by vidocq-runtime-parent");
        }
        if (!PomDependencies.processorPaths(pom).contains(INDEXER)) {
            // A failure where vidocq:checkpom fails the build: once the project has an extension.
            String detail = INDEXER.artifactId() + " missing from annotationProcessorPaths";
            String hint = "Without vidocq-runtime-parent, the project declares the indexer itself, or its beans get"
                    + " no generated factory.";
            return (hasExtensions ? Diagnostic.fail(INDEXER_CHECK, detail, hint)
                    : Diagnostic.warn(INDEXER_CHECK, detail, hint)).asFixable();
        }
        return Diagnostic.ok(INDEXER_CHECK, INDEXER.artifactId() + " on the processor path");
    }

    private static Diagnostic checkModuleDirectives(Facts facts, Map<String, ExtensionCoordinate> extensions) {
        if (facts.moduleInfo() == null) {
            return Diagnostic.warn(MODULE_CHECK, "no " + MODULE_INFO,
                    "Vidocq runs an application as a Java module: declare one, as vidocq create does.");
        }
        List<String> missing = new ArrayList<>();
        missingDirectives(facts, extensions).forEach((id, directives) ->
                directives.forEach(d -> missing.add(id + ": " + d)));
        if (facts.unopenedPackages() != null) {
            facts.unopenedPackages().forEach((pkg, reason) -> missing.add("opens " + pkg + " (" + reason + ")"));
        }
        if (!missing.isEmpty()) {
            return Diagnostic.fail(MODULE_CHECK, "missing from module-info.java — " + String.join(", ", missing),
                    "The application does not compile, the module path refuses it, or its first request fails:"
                            + " vidocq:check-module-info fails the build on it.").asFixable();
        }
        // No count: the pom's extensions are not the VidocqExtension providers the Extensions check counts.
        return Diagnostic.ok(MODULE_CHECK, facts.unopenedPackages() == null
                ? "module-info.java declares what the extensions need; compile to check the packages reached by"
                        + " reflection"
                : "module-info.java declares what the extensions need and opens the packages reached by reflection");
    }

    /**
     * The dev service of each extension that has one, with what it needs to start under {@code vidocq dev},
     * {@code vidocq test} and {@code mvn test} (BUG-20261031-05, -06, -07). A warning: the application still builds,
     * it gets no database, Keycloak or collector without a URL of its own.
     */
    private static Diagnostic checkDevServices(String pom, Map<String, ExtensionCoordinate> extensions) {
        List<String> missing = DevServiceWiring.missing(extensions.keySet(), DevServiceWiring.Declared.of(pom));
        if (!missing.isEmpty()) {
            return Diagnostic.warn(DEV_SERVICES_CHECK, String.join("; ", missing),
                    "Without them the application needs a URL of its own (vidocq.pool.url…) to start: vidocq doctor"
                            + " --fix declares them, as vidocq extension add does.").asFixable();
        }
        long withOne = extensions.keySet().stream().filter(id -> DevServiceWiring.provider(id).isPresent()).count();
        return Diagnostic.ok(DEV_SERVICES_CHECK, withOne == 0 ? "no extension of the project has one"
                : withOne + " wired for vidocq dev, vidocq test and mvn test");
    }

    private static Diagnostic checkMaven(String version) {
        if (olderThan(version, PINNED_MAVEN_VERSION)) {
            return Diagnostic.warn(MAVEN_CHECK,
                    "the wrapper runs Maven " + version + ", Vidocq is built with " + PINNED_MAVEN_VERSION,
                    "Older Mavens print JDK 25 warnings on every build.").asFixable();
        }
        return Diagnostic.ok(MAVEN_CHECK, "Maven " + version + " (wrapper)");
    }

    private static Diagnostic checkPort(Facts facts) {
        if (facts.httpPortFree()) {
            return Diagnostic.ok(PORT_CHECK, facts.httpPort() + " is free");
        }
        return Diagnostic.warn(PORT_CHECK, facts.httpPort() + " is in use",
                "An application serving HTTP will not start: stop what listens there, unless it is this"
                        + " application, or set " + PORT_KEY + " in vidocq.properties.");
    }

    // -------------------------------------------------------------------------
    // Fix
    // -------------------------------------------------------------------------

    /**
     * Repairs what the checks report as fixable in the project in {@code projectDir}: wires the missing codegen
     * bundles and drops the orphans, makes the processor paths append to the parent's or declares the indexer,
     * declares what the dev services of the extensions need, adds the missing {@code module-info.java} directives,
     * writes a missing Maven wrapper and upgrades an older
     * one. Paths carry no version when {@code vidocq-runtime-bom} versions them, else the runtime version of the
     * pom's parent, else {@code defaultRuntimeVersion}.
     *
     * @return whether it changed a file
     */
    public static boolean fix(Path projectDir, String defaultRuntimeVersion, Reporter reporter) throws IOException {
        boolean changed = fixWrapper(projectDir, reporter);
        Facts facts = read(projectDir, port -> true);
        if (facts == null) {
            return changed;
        }
        Map<String, ExtensionCoordinate> extensions = catalogExtensions(facts.pom());
        String pom = fixPom(facts.pom(), extensions, defaultRuntimeVersion, reporter);
        if (!pom.equals(facts.pom())) {
            Files.writeString(projectDir.resolve(POM_XML), pom);
            changed = true;
        }
        if (facts.moduleInfo() != null) {
            List<String> ids = List.copyOf(missingDirectives(facts, extensions).keySet());
            changed |= !ids.isEmpty() && ExtensionEditor.updateModuleInfo(projectDir, ids, true, reporter);
            changed |= openReflectedPackages(projectDir, facts.unopenedPackages(), reporter);
        }
        return changed;
    }

    /**
     * Opens the packages the runtime reaches by reflection, in the block {@code vidocq:complete-module-info} writes
     * (BUG-20261031-01).
     */
    private static boolean openReflectedPackages(Path projectDir, Map<String, String> unopened, Reporter reporter)
            throws IOException {
        if (unopened == null || unopened.isEmpty()) {
            return false;
        }
        Path file = projectDir.resolve(MODULE_INFO);
        List<String> directives = unopened.keySet().stream().map(pkg -> "opens " + pkg + ";").toList();
        Files.writeString(file, ModuleInfoEditor.appendBlock(Files.readString(file), REFLECTION_MARKER, directives));
        reporter.success("module-info.java opens " + String.join(", ", unopened.keySet()),
                "the runtime reaches them by reflection");
        return true;
    }

    private static String fixPom(String pom, Map<String, ExtensionCoordinate> extensions,
                                 String defaultRuntimeVersion, Reporter reporter) {
        String version = PomDependencies.versionsManaged(pom)
                ? null
                : PomDependencies.runtimeVersion(pom).orElse(defaultRuntimeVersion);
        String fixed = DevServiceWiring.wire(pom, extensions.keySet(), version, reporter).pom();
        List<ExtensionCoordinate> paths = PomDependencies.processorPaths(pom);
        for (ExtensionCoordinate orphan : orphanCodegens(paths, extensions)) {
            fixed = PomEditor.removeAnnotationProcessorPath(fixed, orphan).pom();
            reporter.success("Unwired " + orphan.artifactId(), "its extension is not a dependency");
        }
        if (PomDependencies.codegenWiredByPlugin(fixed)) {
            // The plugin adds the indexer and the bundles to the processor path itself.
            return fixed;
        }
        for (String id : extensions.keySet()) {
            Optional<ExtensionCoordinate> bundle = KnownExtensions.codegenBundle(id).filter(b -> !paths.contains(b));
            if (bundle.isPresent()) {
                fixed = PomEditor.addAnnotationProcessorPath(fixed, bundle.get(), version).pom();
                reporter.success("Wired " + bundle.get().artifactId(), "annotationProcessorPaths");
            }
        }
        if (PomDependencies.runtimeVersion(fixed).isPresent()) {
            PomEditor.Result appended = PomEditor.appendToInheritedProcessorPaths(fixed);
            if (appended.changed()) {
                fixed = appended.pom();
                reporter.success("annotationProcessorPaths now appends to the parent's",
                        "keeps the Vauban indexer, " + INDEXER.artifactId());
            }
        } else if (!PomDependencies.processorPaths(fixed).contains(INDEXER)) {
            fixed = PomEditor.addAnnotationProcessorPath(fixed, INDEXER, version).pom();
            reporter.success("Wired " + INDEXER.artifactId(), "the Vauban indexer");
        }
        return fixed;
    }

    private static boolean fixWrapper(Path projectDir, Reporter reporter) throws IOException {
        if (!Files.isRegularFile(projectDir.resolve(POM_XML))) {
            return false;
        }
        Path wrapper = wrapperProperties(projectDir);
        if (wrapper == null || !Files.isRegularFile(wrapper.getParent().getParent().getParent().resolve("mvnw"))) {
            ProjectScaffolder.writeMavenWrapper(projectDir);
            reporter.success("Wrote the Maven wrapper", "mvnw, mvnw.cmd, " + WRAPPER_PROPERTIES);
            return true;
        }
        String properties = Files.readString(wrapper);
        Optional<String> version = mavenVersion(properties);
        if (version.isPresent() && olderThan(version.get(), PINNED_MAVEN_VERSION)) {
            Files.writeString(wrapper, properties.replace("apache-maven-" + version.get() + "-bin.zip",
                    "apache-maven-" + PINNED_MAVEN_VERSION + "-bin.zip")
                    .replace("/apache-maven/" + version.get() + "/", "/apache-maven/" + PINNED_MAVEN_VERSION + "/"));
            Path shown = wrapper.startsWith(projectDir.toAbsolutePath())
                    ? projectDir.toAbsolutePath().relativize(wrapper) : wrapper;
            reporter.success("Maven " + version.get() + " → " + PINNED_MAVEN_VERSION, shown.toString());
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /** The catalog extensions the pom declares, by catalog id, in the pom's order. */
    private static Map<String, ExtensionCoordinate> catalogExtensions(String pom) {
        Map<String, ExtensionCoordinate> extensions = new LinkedHashMap<>();
        for (ExtensionCoordinate dependency : PomDependencies.parse(pom)) {
            KnownExtensions.catalog().stream()
                    .filter(e -> e.coordinate().equals(dependency))
                    .findFirst()
                    .ifPresent(e -> extensions.put(e.id(), dependency));
        }
        return extensions;
    }

    /** The catalog codegen bundles on the processor path whose extension the pom does not declare. */
    private static List<ExtensionCoordinate> orphanCodegens(List<ExtensionCoordinate> paths,
                                                           Map<String, ExtensionCoordinate> extensions) {
        return KnownExtensions.catalog().stream()
                .filter(e -> !extensions.containsKey(e.id()))
                .flatMap(e -> KnownExtensions.codegenBundle(e.id()).stream())
                .filter(paths::contains)
                .toList();
    }

    /** The directives each extension needs that {@code module-info.java} lacks, by extension id. */
    private static Map<String, List<String>> missingDirectives(Facts facts,
                                                               Map<String, ExtensionCoordinate> extensions) {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (String id : extensions.keySet()) {
            List<String> lacking = ModuleInfoEditor.missing(facts.moduleInfo(),
                    KnownExtensions.moduleDirectives(id, facts.applicationPackage()));
            if (!lacking.isEmpty()) {
                missing.put(id, lacking);
            }
        }
        return missing;
    }

    /** The {@code maven-wrapper.properties} of {@code start} or of a parent holding {@code mvnw}, else null. */
    private static Path wrapperProperties(Path start) {
        for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path properties = dir.resolve(WRAPPER_PROPERTIES);
            if (Files.isRegularFile(properties)) {
                return properties;
            }
        }
        return null;
    }

    /** The Maven version a {@code maven-wrapper.properties} downloads. */
    static Optional<String> mavenVersion(String wrapperProperties) {
        Matcher m = MAVEN_DISTRIBUTION.matcher(wrapperProperties);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Whether Maven {@code version} is older than {@code reference}, comparing the numbers of
     * {@code major.minor.patch}; a qualifier such as {@code -rc-4} is ignored.
     */
    static boolean olderThan(String version, String reference) {
        int[] a = numbers(version);
        int[] b = numbers(reference);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] < b[i];
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        String[] parts = version.split("-", 2)[0].split("\\.");
        int[] numbers = new int[3];
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException _) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    /**
     * The port of the default listener: {@value #LISTENER_PORT_KEY}, which wins, else its alias
     * {@value #PORT_KEY}, else {@value #DEFAULT_HTTP_PORT}.
     */
    private static int httpPort(Path projectDir) throws IOException {
        Optional<Path> config = ConfigFile.locate(projectDir, Files::isRegularFile);
        if (config.isEmpty()) {
            return DEFAULT_HTTP_PORT;
        }
        Map<String, String> entries = PropertiesText.entries(Files.readString(config.get()));
        String value = entries.getOrDefault(LISTENER_PORT_KEY, entries.get(PORT_KEY));
        try {
            return value == null ? DEFAULT_HTTP_PORT : Integer.parseInt(value.strip());
        } catch (NumberFormatException _) {
            return DEFAULT_HTTP_PORT;
        }
    }
}
