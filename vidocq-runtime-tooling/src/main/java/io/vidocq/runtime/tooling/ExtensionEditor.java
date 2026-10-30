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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds or removes extensions on a project: the dependency, the codegen bundle's processor path, and the
 * {@code module-info.java} directives. {@code vidocq extension add|remove} and
 * {@code vidocq:add-extension|remove-extension} both run it, so either tool makes the same edit (Vidocq/vidocq#314).
 */
public final class ExtensionEditor {

    private static final String POM_XML = "pom.xml";
    private static final Pattern MODULE = Pattern.compile("\\bmodule\\s+([A-Za-z0-9_.]+)\\s*\\{");

    private ExtensionEditor() {}

    /**
     * What an edit did.
     *
     * @param succeeded         false when it stopped on an error, which was reported
     * @param pomChanged        whether {@code pom.xml} was rewritten
     * @param moduleInfoChanged whether {@code module-info.java} was rewritten
     */
    public record Outcome(boolean succeeded, boolean pomChanged, boolean moduleInfoChanged) {

        private static final Outcome FAILED = new Outcome(false, false, false);
    }

    /**
     * Adds ({@code add}) or removes {@code ids} on the project in {@code projectDir}. The dependency and the codegen
     * path carry no version when {@code vidocq-runtime-bom} versions them (a parent from 0.4.0, or an import of the
     * BOM). Otherwise they carry the runtime version the pom's parent gives, else {@code defaultRuntimeVersion}: a
     * parent before 0.4.0 manages Vidocq artifacts at {@code ${project.version}}, which is the application's own
     * version as soon as it declares one.
     */
    public static Outcome edit(Path projectDir, List<String> ids, boolean add, String defaultRuntimeVersion,
                               Reporter reporter) {
        Path pomPath = projectDir.resolve(POM_XML);
        if (!Files.isRegularFile(pomPath)) {
            reporter.error("No pom.xml in " + projectDir + " — run from a Vidocq project root.");
            return Outcome.FAILED;
        }
        String pom;
        String runtimeVersion;
        try {
            pom = Files.readString(pomPath);
            runtimeVersion = PomDependencies.versionsManaged(pom)
                    ? null
                    : PomDependencies.runtimeVersion(pom).orElse(defaultRuntimeVersion);
        } catch (IOException e) {
            reporter.error("Could not read pom.xml: " + e.getMessage());
            return Outcome.FAILED;
        } catch (IllegalArgumentException e) {
            reporter.error(e.getMessage());
            return Outcome.FAILED;
        }
        // The plugin then wires the codegen bundles at build time, the indexer included (#339).
        boolean wiredByPlugin = PomDependencies.codegenWiredByPlugin(pom);
        boolean droppedInheritedPaths = !wiredByPlugin && PomEditor.dropsInheritedProcessorPaths(pom);
        boolean pomChanged = false;
        ids = withCompanions(pom, ids, add);
        for (String id : ids) {
            ExtensionCoordinate coordinate;
            try {
                coordinate = KnownExtensions.resolve(id);
            } catch (IllegalArgumentException e) {
                reporter.error("Invalid extension id '" + id + "': " + e.getMessage());
                return Outcome.FAILED;
            }
            PomEditor.Result result = editPomFor(pom, id, coordinate, runtimeVersion, add, wiredByPlugin, reporter);
            pom = result.pom();
            pomChanged |= result.changed();
        }
        if (add && droppedInheritedPaths) {
            reportProcessorPathsMode(pom, reporter);
        }

        boolean moduleInfoChanged = updateModuleInfo(projectDir, ids, add, reporter);
        if (pomChanged) {
            try {
                Files.writeString(pomPath, pom);
            } catch (IOException e) {
                reporter.error("Could not write pom.xml: " + e.getMessage());
                return new Outcome(false, false, moduleInfoChanged);
            }
        }
        return new Outcome(true, pomChanged, moduleInfoChanged);
    }

    /**
     * {@code ids} and the companions they bring or leave (BUG-20261030-25): on add, the catalog extensions that go
     * with the extensions the project will then declare; on remove, the declared companions whose pair breaks.
     */
    private static List<String> withCompanions(String pom, List<String> ids, boolean add) {
        Set<String> declared = new LinkedHashSet<>();
        for (ExtensionCoordinate coordinate : PomDependencies.parse(pom)) {
            declared.add(KnownExtensions.idOf(coordinate));
        }
        List<String> all = new ArrayList<>(ids);
        if (add) {
            declared.addAll(ids);
            all.addAll(KnownExtensions.companions(declared));
            return List.copyOf(all);
        }
        Set<String> remaining = new LinkedHashSet<>(declared);
        ids.forEach(remaining::remove);
        for (String id : remaining) {
            List<String> of = KnownExtensions.companionOf(id);
            if (!of.isEmpty() && !remaining.containsAll(of)) {
                all.add(id);
            }
        }
        return List.copyOf(all);
    }

    /**
     * Adds (or removes) the {@code module-info.java} directives of {@code ids}, when the project has one — the
     * module path needs them as much as the pom needs the dependency.
     *
     * @return whether the file changed
     */
    public static boolean updateModuleInfo(Path projectDir, List<String> ids, boolean add, Reporter reporter) {
        Path file = projectDir.resolve("src").resolve("main").resolve("java").resolve("module-info.java");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String source = Files.readString(file);
            String pkg = applicationPackage(source, file.getParent());
            String updated = source;
            for (String id : ids) {
                updated = (add
                        ? ModuleInfoEditor.add(updated, id, directives(projectDir, id, pkg, reporter))
                        : ModuleInfoEditor.remove(updated, id, KnownExtensions.moduleName(id).orElse(null)))
                        .source();
                if (add && pkg == null) {
                    warnUnopenedPackage(id, moduleName(source), reporter);
                }
            }
            if (updated.equals(source)) {
                return false;
            }
            Files.writeString(file, updated);
            reporter.success("module-info.java updated",
                    (add ? "requires/opens for " : "dropped ") + String.join(", ", ids));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            reporter.warning("Could not update module-info.java: " + e.getMessage());
            return false;
        }
    }

    /**
     * The directives {@code id} needs, the {@code opens} of the resource packages it reads included: their folders
     * are made to hold a file first, as the JVM refuses a module that opens a package it lacks (BUG-20261030-11,
     * BUG-20261031-03).
     */
    private static List<String> directives(Path projectDir, String id, String pkg, Reporter reporter)
            throws IOException {
        for (String resourcePackage : KnownExtensions.resourcePackagesToOpen(id)) {
            keepResourcePackage(projectDir, resourcePackage, id, reporter);
        }
        return KnownExtensions.moduleDirectives(id, pkg);
    }

    /**
     * Makes {@code src/main/resources/<resourcePackage>} a package of the module before any script is written: a
     * folder without a file creates none, and a hidden one does not count for the exploded module {@code vidocq dev}
     * runs. It gets a {@code README.md}, which the migration tools ignore; a folder holding a file is left as it is.
     */
    static void keepResourcePackage(Path projectDir, String resourcePackage, String id, Reporter reporter)
            throws IOException {
        String relative = "src/main/resources/" + resourcePackage.replace('.', '/');
        Path dir = projectDir.resolve(relative);
        if (holdsAFile(dir)) {
            return;
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("README.md"), """
                # %s

                `%s` reads the files of this folder. This README keeps the folder a package of the application's \
                module, which `module-info.java` opens (`opens %s;`): the JVM refuses a module that opens a package \
                without a file. Delete it once the folder holds a script, if you like.
                """.formatted(relative.substring("src/main/resources/".length()), id, resourcePackage));
        reporter.success("Created " + relative, "where " + id + " reads its files; opened in module-info.java");
    }

    private static boolean holdsAFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(f -> Files.isRegularFile(f) && !f.getFileName().toString().startsWith("."));
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * The module's name when it is also a package of the sources that holds a class (the scaffold's layout), else
     * null. An empty package, or one with only a {@code package-info.java}, is not in the compiled module: opening
     * it compiles with a warning, then the JVM refuses the module ("Package … not found in module").
     */
    public static String applicationPackage(String moduleInfo, Path javaRoot) throws IOException {
        String name = moduleName(moduleInfo);
        if (name == null) {
            return null;
        }
        Path dir = javaRoot.resolve(name.replace('.', '/'));
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                    .anyMatch(f -> f.endsWith(".java") && !f.equals("package-info.java")) ? name : null;
        }
    }

    /** The name a {@code module-info.java} declares, else null. */
    private static String moduleName(String moduleInfo) {
        Matcher m = MODULE.matcher(moduleInfo);
        return m.find() ? m.group(1) : null;
    }

    /** Tells the user to open the package once it has a class, when {@code id} would have opened it. */
    private static void warnUnopenedPackage(String id, String pkg, Reporter reporter) {
        if (pkg != null && KnownExtensions.moduleDirectives(id, pkg).contains("opens " + pkg)) {
            reporter.warning(id + " reflects on the application package, which has no class yet: add opens " + pkg
                    + "; to module-info.java once it has one.");
        }
    }

    /**
     * Adds or removes one extension's dependency, with its codegen bundle's processor path unless the plugin wires
     * it ({@code wiredByPlugin}); a removal takes a path written before that away all the same.
     */
    private static PomEditor.Result editPomFor(String pom, String id, ExtensionCoordinate coordinate,
                                               String runtimeVersion, boolean add, boolean wiredByPlugin,
                                               Reporter reporter) {
        PomEditor.Result result = add
                ? PomEditor.add(pom, coordinate, runtimeVersion)
                : PomEditor.remove(pom, coordinate);
        PomEditor.Result apt = add && wiredByPlugin
                ? new PomEditor.Result(result.pom(), false)
                : editProcessorPath(result.pom(), id, runtimeVersion, add, reporter);
        if (result.changed()) {
            reporter.success((add ? "Added " : "Removed ") + id, coordinate.toString());
            if (add && !KnownExtensions.isKnown(id)) {
                reporter.note("↳ not in the Vidocq catalog — added as given; verify the coordinate.");
            }
        } else if (add) {
            reporter.note("• " + id + " already present — skipped.");
        } else {
            reporter.warning(id + " not found in pom.xml — skipped.");
        }
        return new PomEditor.Result(apt.pom(), result.changed() || apt.changed());
    }

    /**
     * Extensions shipping an APT codegen bundle need it on the compiler's annotationProcessorPaths, or
     * vidocq:checkpom fails the next build; removing the extension removes it too.
     */
    private static PomEditor.Result editProcessorPath(String pom, String id, String runtimeVersion, boolean add,
                                                      Reporter reporter) {
        var codegen = KnownExtensions.codegenBundle(id);
        if (codegen.isEmpty()) {
            return new PomEditor.Result(pom, false);
        }
        PomEditor.Result apt = add
                ? PomEditor.addAnnotationProcessorPath(pom, codegen.get(), runtimeVersion)
                : PomEditor.removeAnnotationProcessorPath(pom, codegen.get());
        if (apt.changed()) {
            reporter.success((add ? "Wired " : "Unwired ") + codegen.get().artifactId(), "annotationProcessorPaths");
        }
        return apt;
    }

    /**
     * Paths that replace the parent's drop the Vauban indexer (BUG-20261023-02); wiring a codegen bundle makes them
     * append, anything else only warns.
     */
    private static void reportProcessorPathsMode(String pom, Reporter reporter) {
        if (PomEditor.dropsInheritedProcessorPaths(pom)) {
            reporter.warning("annotationProcessorPaths replaces the parent's and so drops the Vauban indexer"
                    + " (vidocq-runtime-core-codegen): declare it with combine.children=\"append\".");
        } else {
            reporter.success("annotationProcessorPaths now appends to the parent's",
                    "keeps the Vauban indexer, vidocq-runtime-core-codegen");
        }
    }
}
