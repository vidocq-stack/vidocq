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
 * <p>jlink links against the JDK of the machine that runs it: an image linked on macOS or Windows cannot start in
 * a Linux container. In that case (or always, with {@code vidocq.docker.linkInContainer=true}) the Dockerfile links
 * the image again in a first stage, inside a Linux JDK image, from the modules {@code vidocq:jlink} staged and the
 * recipe it left in {@code target/jlink-image/} ({@link PortableImage}); the final stage is the same as on Linux
 * (BUG-20261030-17).</p>
 *
 * <h3>Recommended base image</h3>
 * <p>{@code gcr.io/distroless/base-debian12:nonroot} (~20 MB) — no JRE
 * (jlink provides runtime), no shell, non-root user by default. For
 * an interactive debug: {@code gcr.io/distroless/base-debian12:debug}. Without a shell, the entry point runs the
 * image's {@code bin/java} on the application's entry point, whose JVM options the image already carries.</p>
 */
@Mojo(name = "docker",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME)
public class VidocqDockerMojo extends AbstractMojo {

    /**
     * Input jlink runtime image. Default: {@code target/dist} (output of
     * {@code vidocq:jlink}). Must be a path relative to {@code target/}
     * Maven (the generated Dockerfile uses relative COPY).
     */
    @Parameter(defaultValue = "${project.build.directory}/dist", property = "vidocq.runtimeImage")
    private File runtimeImage;

    /**
     * What the image is built on (Vidocq/vidocq#580):
     * <ul>
     *   <li>{@code jlink} (default): the runtime image of {@code vidocq:jlink}, on a base image without a JRE. One
     *       layer, small; an encrypted jar cannot go into it.</li>
     *   <li>{@code jdk}: the distribution of {@code vidocq:package}, on a base image that brings the JDK. The
     *       application keeps its application layer, so an encrypted jar works, and a library that is not a named
     *       module is used as it is.</li>
     * </ul>
     * The choice is explicit: the goal never switches kind by itself, since the base and the size of the image
     * change with it.
     */
    @Parameter(defaultValue = "jlink", property = "vidocq.docker.kind")
    private String kind;

    /**
     * Docker base image. Default: {@code gcr.io/distroless/base-debian12:nonroot} for the {@code jlink} kind,
     * {@code eclipse-temurin:<feature version of the build JDK>-jre} for the {@code jdk} kind.
     */
    @Parameter(property = "vidocq.docker.baseImage")
    private String baseImage;

    /** The directory of the {@code vidocq:package} distribution under {@code target/}, for the {@code jdk} kind. */
    @Parameter(defaultValue = "${project.artifactId}-${project.version}", property = "vidocq.distName")
    private String distName;

    /** The launcher of the {@code vidocq:package} distribution ({@code bin/<name>.sh}), for the {@code jdk} kind. */
    @Parameter(defaultValue = "${project.artifactId}", property = "vidocq.scriptName")
    private String scriptName;

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

    /**
     * Whether the Dockerfile links the runtime image inside a Linux JDK image instead of copying the one
     * {@code vidocq:jlink} linked: {@code auto} (default) does it when that image was not linked for Linux,
     * {@code true} always, {@code false} never (the goal then only warns about a macOS or Windows image).
     */
    @Parameter(defaultValue = "auto", property = "vidocq.docker.linkInContainer")
    private String linkInContainer;

    /**
     * The Linux JDK image that links the runtime image when the Dockerfile links it. Default:
     * {@code eclipse-temurin:<feature version of the build JDK>-jdk}.
     */
    @Parameter(property = "vidocq.docker.jdkImage")
    private String jdkImage;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    /** The directory where {@code vidocq:jlink} stages the modules, relative to {@code target/}. */
    static final String STAGED_MODULES = "jlink-mods";

    /** The base image of the {@code jlink} kind: no JRE, no shell, a non-root user. */
    static final String DISTROLESS = "gcr.io/distroless/base-debian12:nonroot";

    @Override
    public void execute() throws MojoExecutionException {
        Path target = buildDir.toPath();
        String chosen = kind == null ? "jlink" : kind.strip().toLowerCase(java.util.Locale.ROOT);
        Recipe recipe = switch (chosen) {
            case "jlink" -> jlinkRecipe(target);
            case "jdk" -> jdkRecipe(target);
            default -> throw new MojoExecutionException("vidocq.docker.kind is '" + kind + "': use jlink or jdk.");
        };

        Path dockerfile = target.resolve("Dockerfile");
        try {
            Files.writeString(dockerfile, recipe.dockerfile());
            // Only what the Dockerfile copies goes to the Docker daemon, not the whole target/.
            Files.writeString(target.resolve(".dockerignore"), recipe.dockerignore());
            getLog().info("Dockerfile generated: " + dockerfile.toAbsolutePath());
            getLog().info("Build with: docker build -t " + imageTag + " -f " + dockerfile + " " + target);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write Dockerfile", e);
        }

        if (build) {
            runDockerBuild(dockerfile, target);
            getLog().info("Run with: docker run --rm -p " + exposedPort + ":" + exposedPort + " " + imageTag);
        }
    }

    /** A Dockerfile, and the {@code .dockerignore} that sends the daemon only what it copies. */
    private record Recipe(String dockerfile, String dockerignore) {}

    private String base(String byDefault) {
        return baseImage == null || baseImage.isBlank() ? byDefault : baseImage.strip();
    }

    /**
     * The Dockerfile of the {@code jdk} kind: the distribution of {@code vidocq:package} on a base image that
     * brings the JDK, started by the distribution's own launcher, which hands {@code app/} to the runtime: the
     * application runs in its application layer, as it does outside a container. The launcher ends in
     * {@code exec java}, so the JVM is the process the container signals.
     */
    private Recipe jdkRecipe(Path target) throws MojoExecutionException {
        Path launcherScript = target.resolve(distName).resolve("bin").resolve(scriptName + ".sh");
        if (!Files.isRegularFile(launcherScript)) {
            throw new MojoExecutionException("No distribution launcher " + launcherScript
                    + ": run vidocq:package first (mvn package vidocq:docker -Dvidocq.docker.kind=jdk), or set"
                    + " vidocq.distName and vidocq.scriptName to the distribution to copy.");
        }
        String from = base("eclipse-temurin:" + Runtime.version().feature() + "-jre");
        getLog().info("The image copies the vidocq:package distribution " + distName + " onto " + from
                + ": the application keeps its application layer.");
        return new Recipe(header("") + """

                FROM %s

                # Owned by the user that runs the application: a jar copied from a local repository may be
                # readable by its owner only, and the launcher executable by its owner only.
                COPY --chown=65532:65532 %s /opt/app/

                WORKDIR /opt/app
                USER 65532:65532
                EXPOSE %d
                ENTRYPOINT ["/opt/app/bin/%s.sh"]
                """.formatted(from, distName, exposedPort, scriptName), "*\n!" + distName + "\n");
    }

    /** The Dockerfile of the {@code jlink} kind: the runtime image of {@code vidocq:jlink}, copied or linked again. */
    private Recipe jlinkRecipe(Path target) throws MojoExecutionException {
        if (!runtimeImage.isDirectory()) {
            throw new MojoExecutionException(
                    "runtimeImage not found: " + runtimeImage
                            + ". Run vidocq:jlink first (or point runtimeImage to an existing image).");
        }

        // jlink links against the build host's JDK: an image made on macOS or Windows cannot start in
        // the Linux container this goal describes (BUG-20260711-02, #199).
        String platform = nonLinuxPlatform(runtimeImage.toPath());
        boolean inContainer = linksInContainer(platform);
        checkPlatform(platform, inContainer, target);

        String entryPoint = PortableImage.entryPoint(target).orElse(null);
        if (inContainer) {
            String jdk = jdkImage == null || jdkImage.isBlank() ? defaultJdkImage() : jdkImage.strip();
            getLog().info((platform == null ? "The Dockerfile links" : "The runtime image was linked for "
                    + platform + ": the Dockerfile links it again") + " inside " + jdk + " (Linux).");
            return new Recipe(linkingDockerfile(jdk, entryPoint),
                    "*\n!" + STAGED_MODULES + "\n!" + PortableImage.DIRECTORY + "\n");
        }
        // The COPY references dist/ relative to target/ (= build context).
        String distRel = target.relativize(runtimeImage.toPath()).toString();
        return new Recipe(wrappingDockerfile(distRel, entryPoint), "*\n!" + distRel + "\n");
    }

    /**
     * Warns about, or with {@code vidocq.docker.build} refuses, an image linked for {@code platform} that the
     * Dockerfile copies as it is; checks that the recipe of {@code vidocq:jlink} is there when the Dockerfile links.
     */
    private void checkPlatform(String platform, boolean inContainer, Path target) throws MojoExecutionException {
        if (platform != null && !inContainer) {
            String problem = "The runtime image " + runtimeImage + " was linked for " + platform
                    + ", not Linux: the container would fail at start (exec format error). jlink links "
                    + "against the JDK of the machine that runs it, so build the image on Linux, in CI "
                    + "for instance, before wrapping it, or leave vidocq.docker.linkInContainer to auto.";
            if (build) {
                throw new MojoExecutionException(problem);
            }
            getLog().warn(problem + " The Dockerfile is generated anyway.");
        }
        if (inContainer && !PortableImage.exists(target)) {
            throw new MojoExecutionException("No jlink recipe in " + target.resolve(PortableImage.DIRECTORY)
                    + ": run vidocq:jlink first, it stages the modules the Dockerfile links inside a Linux JDK.");
        }
    }

    /** Whether the Dockerfile links the image inside a Linux JDK, for an image linked for {@code platform}. */
    private boolean linksInContainer(String platform) throws MojoExecutionException {
        String mode = linkInContainer == null ? "auto" : linkInContainer.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "auto" -> platform != null;
            case "true" -> true;
            case "false" -> false;
            default -> throw new MojoExecutionException("vidocq.docker.linkInContainer is '" + linkInContainer
                    + "': use auto, true or false.");
        };
    }

    /** {@code eclipse-temurin:<feature version>-jdk}, the version of the JDK that runs the build. */
    static String defaultJdkImage() {
        return "eclipse-temurin:" + Runtime.version().feature() + "-jdk";
    }

    private String header(String note) {
        return """
                # Generated by vidocq-runtime-maven-plugin (vidocq:docker)%s
                # Build context : target/
                # Build         : docker build -t %s -f target/Dockerfile target/
                # Run           : docker run --rm -p %d:%d %s
                """.formatted(note, imageTag, exposedPort, exposedPort, imageTag);
    }

    /**
     * The entry point of the image: its {@code bin/java} on the application's entry point, since the base image has
     * no shell to run the launcher script; the launcher of an image linked without a recipe.
     */
    private String entryPointLine(String entryPoint) {
        return entryPoint == null
                ? "ENTRYPOINT [\"/opt/app/bin/" + launcher + "\"]"
                : "ENTRYPOINT [\"/opt/app/bin/java\", \"-m\", \"" + entryPoint + "\"]";
    }

    /** The Dockerfile that copies the image {@code vidocq:jlink} linked, a Linux one. */
    private String wrappingDockerfile(String distRel, String entryPoint) {
        return header("") + """

                FROM %s

                COPY %s /opt/app/

                WORKDIR /opt/app
                EXPOSE %d
                %s
                """.formatted(base(DISTROLESS), distRel, exposedPort, entryPointLine(entryPoint));
    }

    /**
     * The Dockerfile that links the image in its first stage, inside the Linux JDK {@code jdk}, from the modules and
     * the recipe of {@code vidocq:jlink}. {@code ${JAVA_HOME}/jmods} serves a JDK that ships them; a JDK linked
     * without them (JEP 493) links from its own runtime.
     */
    private String linkingDockerfile(String jdk, String entryPoint) {
        String recipe = PortableImage.DIRECTORY + "/";
        return header("""

                # The runtime image is linked in the first stage, inside a Linux JDK: an image linked
                # on macOS or Windows cannot start in a Linux container.""") + """

                FROM %s AS link
                COPY %s /build/mods
                COPY %s%s /build/jlink.args
                RUN jlink @/build/jlink.args --module-path /build/mods:${JAVA_HOME}/jmods --output /opt/app
                COPY %s%s/ /opt/app/conf/

                FROM %s
                COPY --from=link /opt/app /opt/app
                WORKDIR /opt/app
                EXPOSE %d
                %s
                """.formatted(jdk, STAGED_MODULES, recipe, PortableImage.ARGUMENTS, recipe, PortableImage.CONF,
                base(DISTROLESS), exposedPort, entryPointLine(entryPoint));
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
            } catch (IOException _) {
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
