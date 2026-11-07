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

import io.vidocq.runtime.core.banner.BuildInfo;
import io.vidocq.runtime.maven.buildinfo.BuildIdentity;
import io.vidocq.runtime.maven.buildinfo.GitStatus;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Writes the build identity the startup banner and the dev console read,
 * {@code META-INF/vidocq/build-info/<artifactId>.properties}: the commit (short and full), the branch, the dirty flag
 * when git can tell, and the build time. Under {@code vidocq-runtime-parent}, {@code git-commit-id-maven-plugin}
 * writes that file; in an application without the parent, the plugin binds this goal at {@code initialize}
 * (Vidocq/vidocq#380, {@link ApplicationBuild}).
 *
 * <p>The commit is read from {@code .git} without running {@code git}; without a repository the build does not fail,
 * and the commit reads {@value BuildIdentity#UNVERSIONED}. A reproducible build ({@code project.build.outputTimestamp})
 * keeps that time as its build time.</p>
 */
@Mojo(name = "build-info",
      defaultPhase = LifecyclePhase.INITIALIZE,
      requiresDependencyResolution = ResolutionScope.NONE,
      threadSafe = true)
public class VidocqBuildInfoMojo extends AbstractMojo {

    private static final String LOG_PREFIX = "Vidocq build-info: ";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Where the classes go: the file is written under it. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    File outputDirectory;

    /** Skip writing the build identity; the banner then falls back to the jar date. */
    @Parameter(property = "vidocq.buildInfo.skip", defaultValue = "false")
    boolean skip;

    /** {@code project.build.outputTimestamp}: the build time of a reproducible build, when the pom sets one. */
    @Parameter(defaultValue = "${project.build.outputTimestamp}", readonly = true)
    String outputTimestamp;

    /** The {@code git} command that tells whether the working tree is dirty: a name on the PATH or a path. */
    @Parameter(property = "vidocq.buildInfo.git", defaultValue = "git")
    String git;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info(LOG_PREFIX + "skipped (-Dvidocq.buildInfo.skip=true)");
            return;
        }
        if ("pom".equals(project.getPackaging())) {
            getLog().debug(LOG_PREFIX + "nothing to write for the pom module " + project.getArtifactId());
            return;
        }
        Path basedir = project.getBasedir() == null ? Path.of("").toAbsolutePath() : project.getBasedir().toPath();
        BuildIdentity identity = BuildIdentity.collect(basedir, project.getVersion(),
                BuildIdentity.buildTime(outputTimestamp, Clock.systemUTC()), GitStatus.command(git));
        Path file = outputDirectory.toPath().resolve(BuildInfo.resource(project.getArtifactId()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, identity.properties(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new MojoExecutionException(LOG_PREFIX + "cannot write " + file, e);
        }
        getLog().info(LOG_PREFIX + describe(identity));
    }

    /** {@code 9beafc47 on main, dirty, built 2026-11-07T17:36:03Z}, or {@code unversionned, built …}. */
    static String describe(BuildIdentity identity) {
        if (identity.isUnversioned()) {
            return BuildIdentity.UNVERSIONED + " (no git repository), built " + identity.builtAt();
        }
        StringBuilder text = new StringBuilder(identity.abbreviatedCommit()).append(" on ").append(identity.branch());
        if (identity.dirty() != null) {
            text.append(Boolean.TRUE.equals(identity.dirty()) ? ", dirty" : ", clean");
        }
        return text.append(", built ").append(identity.builtAt()).toString();
    }
}
