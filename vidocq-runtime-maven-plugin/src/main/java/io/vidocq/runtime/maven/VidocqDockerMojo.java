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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a {@code Dockerfile} that packages the jlink runtime image into a
 * minimal Docker image (without additional JRE — jlink contains its own
 * runtime).
 *
 * <p>By default Mojo just generates the {@code Dockerfile} in
 * {@code target/} and explain how the builder. If {@code &lt;build&gt;true&lt;/build&gt;}
 * and the {@code docker} binary is available, Mojo launches
 * {@code docker build -t &lt;imageTag&gt; -f target/Dockerfile target/}.</p>
 *
 * <h3>Recommended base image</h3>
 * <p>{@code gcr.io/distroless/base-debian12:nonroot} (~20 MB) — no JRE
 * (jlink provides runtime), no shell, non-root user by default. For
 * an interactive debug: {@code gcr.io/distroless/base-debian12:debug}.</p>
 */
@Mojo(name = "docker",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME)
public class VidocqDockerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Input jlink runtime image. Default: {@code target/dist} (output of
     * {@code vidocq:jlink}). Must be a path relative to {@code target/}
     * Maven (the generated Dockerfile uses relative COPY).
     */
    @Parameter(defaultValue = "${project.build.directory}/dist", property = "vidocq.runtimeImage")
    private File runtimeImage;

    /** Docker base image. Default: {@code gcr.io/distroless/base-debian12:nonroot}. */
    @Parameter(defaultValue = "gcr.io/distroless/base-debian12:nonroot", property = "vidocq.docker.baseImage")
    private String baseImage;

    /** Tag of the generated image. Default: {@code &lt;artifactId&gt;:&lt;version&gt;}. */
    @Parameter(defaultValue = "${project.artifactId}:${project.version}", property = "vidocq.docker.imageTag")
    private String imageTag;

    /** Exposed port. Default: 8080. */
    @Parameter(defaultValue = "8080", property = "vidocq.docker.exposedPort")
    private int exposedPort;

    /** Launcher name in {@code dist/bin/}. Default: {@code project.artifactId}. */
    @Parameter(defaultValue = "${project.artifactId}", property = "vidocq.launcher")
    private String launcher;

    /**
     * If {@code true}, launch {@code docker build} in addition to the generation.
     * Default: {@code false} (the user launches it himself when he wants).
     */
    @Parameter(defaultValue = "false", property = "vidocq.docker.build")
    private boolean build;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (!runtimeImage.isDirectory()) {
            throw new MojoExecutionException(
                    "runtimeImage not found: " + runtimeImage
                            + ". Run vidocq:jlink first (or point runtimeImage to an existing image).");
        }

        // jlink links against the build host's JDK: an image made on macOS or Windows cannot start in
        // the Linux container this goal describes (BUG-20260711-02, #199).
        String platform = nonLinuxPlatform(runtimeImage.toPath());
        if (platform != null) {
            String problem = "The runtime image " + runtimeImage + " was linked for " + platform
                    + ", not Linux: the container would fail at start (exec format error). jlink links "
                    + "against the JDK of the machine that runs it, so build the image on Linux, in CI "
                    + "for instance, before wrapping it.";
            if (build) {
                throw new MojoExecutionException(problem);
            }
            getLog().warn(problem + " The Dockerfile is generated anyway.");
        }

        Path target = buildDir.toPath();
        Path dockerfile = target.resolve("Dockerfile");

        // The Dockerfile is in target/, the COPY reference dist/ relative to
        // target/ (= build context).
        String distRel = target.relativize(runtimeImage.toPath()).toString();

        String content = """
                # Generated by vidocq-runtime-maven-plugin (vidocq:docker)
                # Build context : target/
                # Build         : docker build -t %s -f target/Dockerfile target/
                # Run           : docker run --rm -p %d:%d %s

                FROM %s

                COPY %s /opt/app/

                WORKDIR /opt/app
                EXPOSE %d
                ENTRYPOINT ["/opt/app/bin/%s"]
                """.formatted(imageTag, exposedPort, exposedPort, imageTag,
                        baseImage, distRel, exposedPort, launcher);

        try {
            Files.writeString(dockerfile, content);
            getLog().info("Dockerfile generated: " + dockerfile.toAbsolutePath());
            getLog().info("Build with: docker build -t " + imageTag + " -f " + dockerfile + " " + target);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write Dockerfile", e);
        }

        if (build) {
            runDockerBuild(dockerfile, target);
        }
    }

    /**
     * The platform the image's {@code bin/java} was built for, read from its executable header, when it
     * is not Linux (ELF): {@code "macOS (Mach-O executable)"} or {@code "Windows (PE executable)"};
     * {@code null} for ELF, or when the launcher cannot be read or recognised.
     */
    static String nonLinuxPlatform(Path image) {
        for (String name : new String[] {"java", "java.exe"}) {
            Path java = image.resolve("bin").resolve(name);
            if (!Files.isRegularFile(java)) continue;
            byte[] head = new byte[4];
            try (var in = Files.newInputStream(java)) {
                if (in.readNBytes(head, 0, 4) < 2) return null;
            } catch (IOException e) {
                return null;
            }
            int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
            if (magic == 0x7F454C46) return null; // ELF
            if (magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCEFAEDFE || magic == 0xCFFAEDFE
                    || magic == 0xCAFEBABE) {
                return "macOS (Mach-O executable)";
            }
            if (head[0] == 'M' && head[1] == 'Z') return "Windows (PE executable)";
            return null;
        }
        return null;
    }

    private void runDockerBuild(Path dockerfile, Path context) throws MojoExecutionException {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "build",
                    "-t", imageTag,
                    "-f", dockerfile.toString(),
                    context.toString())
                    .inheritIO();
            getLog().info("Running: " + String.join(" ", pb.command()));
            int rc = pb.start().waitFor();
            if (rc != 0) {
                throw new MojoExecutionException("docker build failed (rc=" + rc + ")");
            }
            getLog().info("Docker image built: " + imageTag);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Unable to launch 'docker build' (docker binary not found?). "
                            + "Run manually: docker build -t " + imageTag + " -f "
                            + dockerfile + " " + context, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("docker build interrupted", e);
        }
    }
}
