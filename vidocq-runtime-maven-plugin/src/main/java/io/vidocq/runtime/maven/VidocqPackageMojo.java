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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.core.LayerSplit;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package a Vidocq application as a standalone ZIP distribution.
 * <p>
 * Output structure:
 * <pre>
 * myapp-1.0/
 *   bin/run.sh (Unix launcher)
 *   bin/run.cmd (Windows launcher)
 *   lib/*.jar (JAR application + dependencies)
 * </pre>
 *
 * <p><b>Packages a Vidocq application as a standalone ZIP distribution.</b></p>
 */
@Mojo(name = "package",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME)
public class VidocqPackageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "io.vidocq.runtime.core.Vidocq", property = "vidocq.mainClass")
    private String mainClass;

    /**
     * Java module of the application's main class: the legacy layout ({@code vidocq.package.layer=false}) launches
     * {@code <mainModule>/<mainClass>}. The layer-mode scripts always boot the runtime with
     * {@code -Dvidocq.app.path} and {@code -Dvidocq.app.main}: a trampoline launched with {@code app/} on the module
     * path loaded the application twice, in the boot layer and in the application layer (BUG-20261114-10).
     */
    @Parameter(property = "vidocq.mainModule")
    private String mainModule;

    /**
     * Universal-loader mode (default): the application jar lands in {@code app/} (not
     * {@code lib/}) and the launch scripts boot the runtime with
     * {@code -Dvidocq.app.path="$BASEDIR/app"} — the application resolves into a child
     * module layer defined by the Vauban class loader (classes woven at definition, no
     * instrumentation agent). Set to {@code false} to restore the legacy
     * everything-in-lib layout, without an application layer: its launchers pass
     * {@code -Dvidocq.app.layer.off=true}, which a {@code Vidocq.run} trampoline honours.
     */
    @Parameter(defaultValue = "true", property = "vidocq.package.layer")
    private boolean layerMode;

    /**
     * {@code all} (the default since 0.4.0): the application layer holds the project's classes and every dependency
     * outside the runtime's closure (Vidocq/vidocq#247): {@code lib/} keeps the parent layer and {@code app/} the
     * application jar with its libraries. {@code classes}: the application layer holds the project's classes only.
     * Left unset, a build that {@code all} cannot serve degrades to {@code classes} with a warning; set explicitly,
     * {@code all} fails it.
     */
    @Parameter(property = "vidocq.app.layer")
    private String appLayer;

    /** Comma-separated patterns ({@code module} or {@code groupId:artifactId}) kept in the parent layer under {@code all}. */
    @Parameter(property = "vidocq.app.layer.keep")
    private String appLayerKeep;

    /** The modules kept in the parent layer by a pin, with the reason: set by {@link #execute()} under {@code all}. */
    private Map<String, String> pinned = Map.of();

    /** The mode the distribution is built under: set by {@link #execute()}, {@code classes} when {@code all} degrades. */
    private LayerSplit.Mode mode;

    /** The distribution's {@code app/} directory under the layer launch, whose modules may require JDK modules. */
    private Path appDir;

    @Parameter(defaultValue = "${project.artifactId}", property = "vidocq.scriptName")
    private String scriptName;

    /**
     * Extra JVM arguments inserted in the generated launchers. Genuinely optional: an empty
     * {@code defaultValue} is <em>not</em> injected by Maven, so an omitted {@code <jvmArgs>}
     * leaves this field {@code null} — always read it through {@link #jvmArgsLine()}.
     */
    @Parameter(defaultValue = "", property = "vidocq.jvmArgs")
    private String jvmArgs;

    @Parameter(defaultValue = "${project.artifactId}-${project.version}", property = "vidocq.distName")
    private String distName;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    @javax.inject.Inject
    private MavenProjectHelper projectHelper;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    @Component
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Vidocq - Packaging distribution: " + distName);

        try {
            Path distRoot = buildDir.toPath().resolve(distName);
            Path binDir = distRoot.resolve("bin");
            Path libDir = distRoot.resolve("lib");
            Files.createDirectories(binDir);
            Files.createDirectories(libDir);

            // Copy application JAR. Universal-loader mode: the application lands in its
            // own app/ directory — the launch scripts hand it to the runtime through
            // -Dvidocq.app.path, so it resolves into a Vauban-defined module layer
            // instead of the JVM module path.
            Path appJar = project.getArtifact().getFile().toPath();
            Path appJarDir = layerMode ? distRoot.resolve("app") : libDir;
            Files.createDirectories(appJarDir);
            this.appDir = layerMode ? appJarDir : null;
            Files.copy(appJar, appJarDir.resolve(appJar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);

            // Copy dependency JARs. A dependency for which vidocq:generate parked
            // cross-module classes (target/vidocq-patches/<artifactId>) is copied as an
            // enriched jar carrying its own generated classes — no JPMS split package,
            // no --patch-module needed at launch. A dependency patched with a generated
            // module-info by vauban:modularize (target/vauban-modularized/) ships as that
            // copy, under the original file name.
            List<Packaged> packaged = packagedDependencies();

            // vidocq.app.layer=all: the parent layer stays in lib/, the other libraries ship in app/.
            Set<Path> toApp = splitForAppLayer(packaged);
            List<DistributionSbom.Component> shipped = new ArrayList<>();
            for (Packaged p : packaged) {
                Path destDir = toApp.contains(p.src()) ? appJarDir : libDir;
                ship(p, destDir);
                Artifact a = p.artifact();
                shipped.add(new DistributionSbom.Component(a.getGroupId(), a.getArtifactId(), a.getVersion(),
                        destDir.resolve(p.src().getFileName().toString())));
            }
            // The CycloneDX SBOM of what the distribution ships (Vidocq/vidocq#270), inside it.
            DistributionSbom.write(distRoot.resolve(distName + "-cyclonedx.json"),
                    new DistributionSbom.Subject(project.getGroupId(), project.getArtifactId(), project.getVersion()),
                    shipped);

            // Generate launch scripts (module-path based)
            generateShScript(binDir);
            generateCmdScript(binDir);

            // Create ZIP
            Path zipFile = buildDir.toPath().resolve(distName + "-dist.zip");
            createZip(distRoot, zipFile);

            // Attach artifact
            projectHelper.attachArtifact(project, "zip", "dist", zipFile.toFile());

            getLog().info("Distribution created: " + zipFile);

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create distribution", e);
        }
    }

    /** The dependency jars to ship, each rebased on its enriched or modularized copy; dev-only jars dropped. */
    private List<Packaged> packagedDependencies() throws IOException, MojoExecutionException {
        List<Packaged> packaged = new ArrayList<>();
        var packaging = DependencyBricks.packaging(project, buildDir.toPath(), repoSystem, repoSession,
                remoteRepos, getLog()::warn, false);
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() == null || !"jar".equals(artifact.getType())) {
                continue;
            }
            if (DevOnlyJars.isDevOnly(artifact.getFile().toPath())) {
                getLog().warn(DevOnlyJars.droppedWarning(artifact.getArtifactId()));
            } else {
                packaged.add(packaged(packaging, artifact));
            }
        }
        return packaged;
    }

    /** One dependency jar to ship, once its copy is rebased: the copy if there is one, else the jar itself. */
    private Packaged packaged(DependencyBricks.Packaging packaging, Artifact artifact)
            throws IOException, MojoExecutionException {
        Path original = artifact.getFile().toPath();
        packaging.rebase(new EnrichedJars.Scanned(original, artifact.getArtifactId(),
                artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion()),
                getLog()::info);
        Path src = EnrichedJars.resolve(buildDir.toPath(), original);
        boolean enriched = EnrichedJars.isEnriched(buildDir.toPath(), original);
        if (enriched) {
            getLog().info("Packaging enriched copy of " + original.getFileName()
                    + " (vidocq:generate added its generated code and declared it)");
        } else if (!src.equals(original)) {
            getLog().info("Packaging modularized copy of " + original.getFileName()
                    + " (vauban:modularize generated its module descriptor)");
        }
        return new Packaged(artifact, src, enriched,
                JpmsPatches.patchDirFor(buildDir.toPath(), artifact.getArtifactId()));
    }

    /** Copies {@code p} into {@code destDir}, enriched with its parked classes when it has some and no copy does. */
    private void ship(Packaged p, Path destDir) throws IOException {
        if (!p.enriched() && Files.isDirectory(p.patchDir())) {
            JpmsPatches.enrich(p.src(), p.patchDir(), destDir.resolve(p.src().getFileName().toString()));
            getLog().info("Enriched " + p.src().getFileName()
                    + " with its generated classes (JPMS cross-module)");
        } else {
            Files.copy(p.src(), destDir.resolve(p.src().getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The JVM-argument fragment spliced into a launcher: empty when {@code <jvmArgs>} is absent,
     * blank, or left at its (uninjected) default, else the arguments preceded by a single space.
     */
    private String jvmArgsLine() {
        return jvmArgs == null || jvmArgs.isBlank() ? "" : " " + jvmArgs.strip();
    }

    /** A dependency jar to ship: the file to copy ({@code src}), whether it is an enriched copy, its parked classes. */
    private record Packaged(Artifact artifact, Path src, boolean enriched, Path patchDir) {}

    /**
     * The jars that ship in {@code app/}: none unless {@code vidocq.app.layer=all}, where every dependency outside the
     * runtime's closure and the pins goes there, and {@link #pinned} records what stays in {@code lib/} by a pin. The
     * application jar is the application module, not a candidate. The reads come from Maven, as
     * {@link ApplicationLaunch#layered} computes them, so that a kept automatic module keeps its dependencies.
     */
    private Set<Path> splitForAppLayer(List<Packaged> packaged) throws MojoExecutionException {
        mode = ApplicationLaunch.effectiveMode(appLayer, layerMode, "vidocq.package.layer", getLog()::warn);
        pinned = Map.of();
        if (mode != LayerSplit.Mode.ALL) {
            return Set.of();
        }
        boolean requested = LayerSplit.requested(appLayer);
        List<LayerSplit.Candidate> candidates = new ArrayList<>();
        List<Artifact> artifacts = new ArrayList<>();
        Map<String, String> moduleByCoordinates = new LinkedHashMap<>();
        // No jar is forced into lib/ for its parked classes, unlike run and dev: the package ships it enriched with
        // them, so no --patch-module is needed at launch.
        for (Packaged p : packaged) {
            Artifact a = p.artifact();
            LayerSplit.Candidate candidate;
            try {
                candidate = LayerSplit.candidate(p.src(), a.getGroupId() + ":" + a.getArtifactId());
            } catch (IOException e) {
                if (!requested) {
                    getLog().warn(ApplicationLaunch.unreadableModuleWarning(e.getMessage()));
                    continue;
                }
                throw new MojoExecutionException("vidocq.app.layer=all reads the module of every dependency: "
                        + e.getMessage() + ". Give the jar an Automatic-Module-Name, or use vidocq.app.layer=classes.",
                        e);
            }
            candidates.add(candidate);
            artifacts.add(a);
            moduleByCoordinates.put(a.getGroupId() + ":" + a.getArtifactId(), candidate.descriptor().name());
        }
        LayerSplit.Result split;
        try {
            split = LayerSplit.split(candidates, Set.of(), LayerSplit.patterns(appLayerKeep), Map.of(),
                    ApplicationLaunch.declaredReads(artifacts, moduleByCoordinates));
        } catch (IllegalStateException e) {
            if (requested) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            getLog().warn(ApplicationLaunch.splitFallbackWarning(e.getMessage()));
            mode = LayerSplit.Mode.CLASSES;
            return Set.of();
        }
        pinned = split.pinned();
        Set<Path> toApp = new HashSet<>(split.applicationPaths());
        getLog().info(ApplicationLaunch.layerSummary(toApp.size(), pinned.size())
                + " (the libraries ship in app/, the parent layer in lib/)");
        return toApp;
    }

    /**
     * The JVM options of the application layer in a launcher, each followed by the line continuation. The legacy
     * layout has no layer, and says so: a trampoline main would otherwise load the application a second time into
     * one (BUG-20261114-10).
     */
    private String appLayerOptions(String continuation) {
        // The mode is always written, classes too: a runtime that finds it unset takes the default, all.
        String options = "\n  -Dvidocq.app.layer=" + effectiveMode().name().toLowerCase(java.util.Locale.ROOT)
                + " " + continuation;
        if (!layerMode) {
            options += "\n  -D" + LayerSplit.OFF_PROPERTY + "=true " + continuation;
        }
        if (!pinned.isEmpty()) {
            options += "\n  \"-D" + LayerSplit.PINNED_PROPERTY + "=" + LayerSplit.encodePinned(pinned) + "\" "
                    + continuation;
        }
        return options;
    }

    /**
     * The {@code --add-modules} value of the layer scripts: the module path, plus the JDK modules the modules of
     * {@code app/} require, which only the boot layer can define (vidocq#254).
     */
    private String addModules() throws IOException {
        if (appDir == null || !Files.isDirectory(appDir)) {
            return "ALL-MODULE-PATH";
        }
        try (var files = Files.list(appDir)) {
            return ApplicationLaunch.addModules(files.filter(f -> f.getFileName().toString().endsWith(".jar"))
                    .sorted().toList());
        }
    }

    /**
     * The launchers pass {@code JAVA_OPTS} to the JVM after {@code <jvmArgs>}, so that an option set when the
     * application is started wins, unquoted so that each option is its own word: {@code "$@"} goes to the
     * application (BUG-20261030-17, item 4).
     */
    void generateShScript(Path binDir) throws IOException, MojoExecutionException {
        String jvmArgsLine = jvmArgsLine();
        String script;
        if (layerMode) {
            String appMain = appMainProperty();
            script = """
                    #!/bin/sh
                    BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                    exec "$JAVA"%s $JAVA_OPTS \\
                      --module-path "$BASEDIR/lib" \\
                      --add-modules %s \\%s
                      -Dvidocq.app.path="$BASEDIR/app" \\%s
                      --module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq \\
                      "$@"
                    """.formatted(jvmArgsLine, addModules(), appLayerOptions("\\"),
                    appMain == null ? "" : "\n  -Dvidocq.app.main=" + appMain + " \\");
        } else {
            script = """
                    #!/bin/sh
                    BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                    exec "$JAVA"%s $JAVA_OPTS \\
                      --module-path "$BASEDIR/lib" \\%s
                      --module %s \\
                      "$@"
                    """.formatted(jvmArgsLine, appLayerOptions("\\"), legacyModuleRef());
        }

        Path shFile = binDir.resolve(scriptName + ".sh");
        Files.writeString(shFile, withJdkCheck(script, "#!/bin/sh\n", SH_JDK_CHECK));
        if (!shFile.toFile().setExecutable(true)) {
            getLog().warn("Could not make " + shFile + " executable: run chmod +x on it.");
        }
    }

    /**
     * What the shell launcher does before it starts the JVM (vidocq#102): run the {@code java} of
     * {@code JAVA_HOME} when it is set, as the build does, and stop with a sentence that names the JDK it found and
     * the one it needs, instead of letting an older JDK fail on a module descriptor it cannot read (class file
     * version 69).
     */
    private static final String SH_JDK_CHECK = """
            JAVA=java
            [ -n "$JAVA_HOME" ] && JAVA="$JAVA_HOME/bin/java"
            JAVA_MAJOR=$("$JAVA" -version 2>&1 | sed -n 's/^.* version "\\([0-9]*\\).*/\\1/p' | head -n 1)
            if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ]; then
              echo "This application needs Java 25 or later: $JAVA is Java ${JAVA_MAJOR:-unknown}. Set JAVA_HOME to a JDK 25." >&2
              exit 1
            fi
            """;

    /** The same check for the Windows launcher (vidocq#102). */
    private static final String CMD_JDK_CHECK = """
            set "JAVA=java"
            if defined JAVA_HOME set "JAVA=%JAVA_HOME%\\bin\\java"
            set "JAVA_MAJOR="
            for /f "tokens=3" %%v in ('"%JAVA%" -version 2^>^&1') do if not defined JAVA_MAJOR for /f "delims=." %%m in ("%%~v") do set "JAVA_MAJOR=%%m"
            if not defined JAVA_MAJOR goto wrongjava
            if %JAVA_MAJOR% LSS 25 goto wrongjava
            goto javaok
            :wrongjava
            if not defined JAVA_MAJOR set "JAVA_MAJOR=unknown"
            echo This application needs Java 25 or later: %JAVA% is Java %JAVA_MAJOR%. Set JAVA_HOME to a JDK 25. 1>&2
            exit /b 1
            :javaok
            """;

    /** Puts the JDK check right after the first line of a launcher. */
    private static String withJdkCheck(String script, String firstLine, String check) {
        return firstLine + check + script.substring(firstLine.length());
    }

    void generateCmdScript(Path binDir) throws IOException, MojoExecutionException {
        String jvmArgsLine = jvmArgsLine();
        String script;
        if (layerMode) {
            String appMain = appMainProperty();
            script = """
                    @echo off
                    set BASEDIR=%%~dp0..
                    "%%JAVA%%"%s %%JAVA_OPTS%% ^
                      --module-path "%%BASEDIR%%\\lib" ^
                      --add-modules %s ^%s
                      -Dvidocq.app.path="%%BASEDIR%%\\app" ^%s
                      --module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq ^
                      %%*
                    """.formatted(jvmArgsLine, addModules(), appLayerOptions("^"),
                    appMain == null ? "" : "\n  -Dvidocq.app.main=" + appMain + " ^");
        } else {
            script = """
                    @echo off
                    set BASEDIR=%%~dp0..
                    "%%JAVA%%"%s %%JAVA_OPTS%% ^
                      --module-path "%%BASEDIR%%\\lib" ^%s
                      --module %s ^
                      %%*
                    """.formatted(jvmArgsLine, appLayerOptions("^"), legacyModuleRef());
        }

        Files.writeString(binDir.resolve(scriptName + ".cmd"), withJdkCheck(script, "@echo off\n", CMD_JDK_CHECK));
    }

    /**
     * The {@code module/class} the legacy layout ({@code vidocq.package.layer=false}) hands to
     * {@code --module}, which takes a module name, never a bare class (BUG-20260710-02, #198): the
     * runtime's own main by default, {@code <mainModule>/<mainClass>} for an application main class,
     * and a value already written {@code module/class} as is.
     */
    private String legacyModuleRef() throws MojoExecutionException {
        if (mainClass == null || mainClass.isBlank()
                || ApplicationMainClass.RUNTIME_MAIN_CLASS.equals(mainClass)) {
            return "io.vidocq.runtime.core/" + ApplicationMainClass.RUNTIME_MAIN_CLASS;
        }
        if (mainClass.contains("/")) {
            return mainClass;
        }
        if (mainModule == null || mainModule.isBlank()) {
            throw new MojoExecutionException("vidocq:package with vidocq.package.layer=false launches the main class "
                    + "through --module, which needs its module: set vidocq.mainModule (the module of "
                    + mainClass + "), or write vidocq.mainClass as <module>/" + mainClass + ".");
        }
        return mainModule + "/" + mainClass;
    }

    /**
     * The mode of the launchers: the one {@link #execute()} settled on, or, for a script written on its own, the
     * configured one ({@code classes} when the layer mode is off).
     */
    private LayerSplit.Mode effectiveMode() {
        if (mode != null) {
            return mode;
        }
        return layerMode ? LayerSplit.Mode.of(appLayer) : LayerSplit.Mode.CLASSES;
    }

    /**
     * The application main class to run through the layer, derived from {@code mainClass}
     * — {@code null} when it is the runtime's own main (the default) or blank. A legacy
     * {@code module/class} reference keeps only its class part. The rule is shared with
     * {@code vidocq:idea}, see {@link ApplicationMainClass}.
     */
    private String appMainProperty() {
        return ApplicationMainClass.normalize(mainClass);
    }

    private void createZip(Path sourceDir, Path zipFile) throws IOException {
        try (var zos = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(zipFile)))) {
            Path parent = sourceDir.getParent();
            try (var walker = Files.walk(sourceDir)) {
                walker.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String entryName = parent.relativize(file).toString();
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }
}
