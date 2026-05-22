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
 * Package une application Vidocq en distribution ZIP autonome.
 * <p>
 * Structure de sortie :
 * <pre>
 * myapp-1.0/
 *   bin/run.sh      (lanceur Unix)
 *   bin/run.cmd     (lanceur Windows)
 *   lib/*.jar       (JAR applicatif + dépendances)
 * </pre>
 *
 * <p><b>Packages a Vidocq application as a standalone distribution ZIP.</b></p>
 */
@Mojo(name = "package",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME)
public class VidocqPackageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "io.vidocq.runtime.core.Vidocq", property = "vidocq.mainClass")
    private String mainClass;

    @Parameter(defaultValue = "${project.artifactId}", property = "vidocq.scriptName")
    private String scriptName;

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

            // Copy application JAR
            Path appJar = project.getArtifact().getFile().toPath();
            Files.copy(appJar, libDir.resolve(appJar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);

            // Copy dependency JARs
            for (var artifact : project.getArtifacts()) {
                if (artifact.getFile() != null && "jar".equals(artifact.getType())) {
                    Path src = artifact.getFile().toPath();
                    Files.copy(src, libDir.resolve(src.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
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

    private void generateShScript(Path binDir) throws IOException {
        String jvmArgsLine = jvmArgs.isBlank() ? "" : " " + jvmArgs;
        String script = """
                #!/bin/sh
                BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                exec java%s \\
                  --module-path "$BASEDIR/lib" \\
                  --module %s \\
                  "$@"
                """.formatted(jvmArgsLine, mainClass);

        Path shFile = binDir.resolve(scriptName + ".sh");
        Files.writeString(shFile, script);
        shFile.toFile().setExecutable(true);
    }

    private void generateCmdScript(Path binDir) throws IOException {
        String jvmArgsLine = jvmArgs.isBlank() ? "" : " " + jvmArgs;
        String script = """
                @echo off
                set BASEDIR=%%~dp0..
                java%s ^
                  --module-path "%%BASEDIR%%\\lib" ^
                  --module %s ^
                  %%*
                """.formatted(jvmArgsLine, mainClass);

        Files.writeString(binDir.resolve(scriptName + ".cmd"), script);
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
