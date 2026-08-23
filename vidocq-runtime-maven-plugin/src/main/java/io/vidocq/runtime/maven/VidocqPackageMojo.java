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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.*;
import java.nio.file.*;
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
     * Java module of the application's {@code @VidocqMain} trampoline. When set together
     * with a non-default {@link #mainClass}, the layer-mode scripts launch the trampoline
     * directly ({@code --module <mainModule>/<mainClass>} with {@code app/} on the module
     * path) — {@code Vidocq.run()} then re-layers the application through the Vauban
     * class loader; otherwise the scripts boot the runtime with
     * {@code -Dvidocq.app.path}.
     */
    @Parameter(property = "vidocq.mainModule")
    private String mainModule;

    /**
     * Universal-loader mode (default): the application jar lands in {@code app/} (not
     * {@code lib/}) and the launch scripts boot the runtime with
     * {@code -Dvidocq.app.path="$BASEDIR/app"} — the application resolves into a child
     * module layer defined by the Vauban class loader (classes woven at definition, no
     * instrumentation agent). Set to {@code false} to restore the legacy
     * everything-in-lib layout.
     */
    @Parameter(defaultValue = "true", property = "vidocq.package.layer")
    private boolean layerMode;

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
            Path appDir = layerMode ? distRoot.resolve("app") : libDir;
            Files.createDirectories(appDir);
            Files.copy(appJar, appDir.resolve(appJar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);

            // Copy dependency JARs. A dependency for which vidocq:generate parked
            // cross-module classes (target/vidocq-patches/<artifactId>) is copied as an
            // enriched jar carrying its own generated classes — no JPMS split package,
            // no --patch-module needed at launch. A dependency patched with a generated
            // module-info by vidocq:modularize (target/vidocq-modularized/) ships as that
            // copy, under the original file name.
            for (var artifact : project.getArtifacts()) {
                if (artifact.getFile() != null && "jar".equals(artifact.getType())) {
                    Path original = artifact.getFile().toPath();
                    Path src = ModularizedJars.resolve(buildDir.toPath(), original);
                    if (!src.equals(original)) {
                        getLog().info("Packaging modularized copy of " + original.getFileName()
                                + " (vidocq:modularize generated its module descriptor)");
                    }
                    Path patchDir = JpmsPatches.patchDirFor(buildDir.toPath(), artifact.getArtifactId());
                    if (Files.isDirectory(patchDir)) {
                        JpmsPatches.enrich(src, patchDir, libDir.resolve(src.getFileName().toString()));
                        getLog().info("Enriched " + src.getFileName()
                                + " with its generated classes (JPMS cross-module)");
                    } else {
                        Files.copy(src, libDir.resolve(src.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

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

    /**
     * The JVM-argument fragment spliced into a launcher: empty when {@code <jvmArgs>} is absent,
     * blank, or left at its (uninjected) default, else the arguments preceded by a single space.
     */
    private String jvmArgsLine() {
        return jvmArgs == null || jvmArgs.isBlank() ? "" : " " + jvmArgs.strip();
    }

    void generateShScript(Path binDir) throws IOException {
        String jvmArgsLine = jvmArgsLine();
        String script;
        if (layerMode && trampolineRef() != null) {
            // @VidocqMain trampoline launch: the app rides the module path; Vidocq.run()
            // re-layers it through the Vauban class loader — one launch shape for the
            // IDE, the dev mode and the distribution.
            script = """
                    #!/bin/sh
                    BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                    exec java%s \\
                      --module-path "$BASEDIR/lib:$BASEDIR/app" \\
                      --add-modules ALL-MODULE-PATH \\
                      --module %s \\
                      "$@"
                    """.formatted(jvmArgsLine, trampolineRef());
        } else if (layerMode) {
            String appMain = appMainProperty();
            script = """
                    #!/bin/sh
                    BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                    exec java%s \\
                      --module-path "$BASEDIR/lib" \\
                      --add-modules ALL-MODULE-PATH \\
                      -Dvidocq.app.path="$BASEDIR/app" \\%s
                      --module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq \\
                      "$@"
                    """.formatted(jvmArgsLine,
                    appMain == null ? "" : "\n  -Dvidocq.app.main=" + appMain + " \\");
        } else {
            script = """
                    #!/bin/sh
                    BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                    exec java%s \\
                      --module-path "$BASEDIR/lib" \\
                      --module %s \\
                      "$@"
                    """.formatted(jvmArgsLine, mainClass);
        }

        Path shFile = binDir.resolve(scriptName + ".sh");
        Files.writeString(shFile, script);
        shFile.toFile().setExecutable(true);
    }

    void generateCmdScript(Path binDir) throws IOException {
        String jvmArgsLine = jvmArgsLine();
        String script;
        if (layerMode && trampolineRef() != null) {
            script = """
                    @echo off
                    set BASEDIR=%%~dp0..
                    java%s ^
                      --module-path "%%BASEDIR%%\\lib;%%BASEDIR%%\\app" ^
                      --add-modules ALL-MODULE-PATH ^
                      --module %s ^
                      %%*
                    """.formatted(jvmArgsLine, trampolineRef());
        } else if (layerMode) {
            String appMain = appMainProperty();
            script = """
                    @echo off
                    set BASEDIR=%%~dp0..
                    java%s ^
                      --module-path "%%BASEDIR%%\\lib" ^
                      --add-modules ALL-MODULE-PATH ^
                      -Dvidocq.app.path="%%BASEDIR%%\\app" ^%s
                      --module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq ^
                      %%*
                    """.formatted(jvmArgsLine,
                    appMain == null ? "" : "\n  -Dvidocq.app.main=" + appMain + " ^");
        } else {
            script = """
                    @echo off
                    set BASEDIR=%%~dp0..
                    java%s ^
                      --module-path "%%BASEDIR%%\\lib" ^
                      --module %s ^
                      %%*
                    """.formatted(jvmArgsLine, mainClass);
        }

        Files.writeString(binDir.resolve(scriptName + ".cmd"), script);
    }

    /**
     * The {@code module/class} reference of the application trampoline, or {@code null}
     * when {@link #mainModule} is absent or {@link #mainClass} is the runtime default.
     */
    private String trampolineRef() {
        if (mainModule == null || mainModule.isBlank()) return null;
        String appMain = appMainProperty();
        return appMain == null ? null : mainModule + "/" + appMain;
    }

    /**
     * The application main class to run through the layer, derived from {@code mainClass}
     * — {@code null} when it is the runtime's own main (the default) or blank. A legacy
     * {@code module/class} reference keeps only its class part.
     */
    private String appMainProperty() {
        if (mainClass == null || mainClass.isBlank()
                || "io.vidocq.runtime.core.Vidocq".equals(mainClass)) {
            return null;
        }
        int slash = mainClass.indexOf('/');
        return slash >= 0 ? mainClass.substring(slash + 1) : mainClass;
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
