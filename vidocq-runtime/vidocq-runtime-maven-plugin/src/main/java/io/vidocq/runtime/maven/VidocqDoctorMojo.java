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

import io.vidocq.runtime.tooling.Diagnostic;
import io.vidocq.runtime.tooling.Diagnostics;
import io.vidocq.runtime.tooling.DoctorContext;
import io.vidocq.runtime.tooling.DoctorFacts;
import io.vidocq.runtime.tooling.DoctorReport;
import io.vidocq.runtime.tooling.KeyDoctor;
import io.vidocq.runtime.tooling.ProjectDoctor;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Checks the environment and the project, as {@code vidocq doctor} does: both diagnose the facts
 * {@link DoctorFacts} gathers with the same {@link Diagnostics} (Vidocq/vidocq#314). A failed check fails the build;
 * a warning does not.
 *
 * <pre>mvn vidocq:doctor [-Dvidocq.doctor.fix] [-Dvidocq.doctor.verbose]</pre>
 *
 * <p>Has no default phase. The extensions are those of the project's runtime dependencies, which Maven resolves
 * before the goal runs. The project checks of {@link ProjectDoctor} read its sources, and {@code -Dvidocq.doctor.fix}
 * first repairs what they report as fixable (Vidocq/vidocq#339). The checks of {@link KeyDoctor} come last: the
 * descriptions of the configuration keys those dependencies and the project ship, and the values of
 * {@code vidocq.properties} against them, as warnings.</p>
 */
@Mojo(name = "doctor",
      requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class VidocqDoctorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Also give the hint of a check that passed. */
    @Parameter(property = "vidocq.doctor.verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * First repair what the project checks report as fixable: the codegen paths, {@code module-info.java}, the
     * Maven wrapper.
     */
    @Parameter(property = "vidocq.doctor.fix", defaultValue = "false")
    private boolean fix;

    /** The version a codegen path pins when neither the BOM nor the pom's parent gives one: this plugin's own. */
    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String runtimeVersion;

    // package-private: a test feeds its own facts.
    DoctorContext context() {
        return DoctorFacts.gather(project.getBasedir().toPath(), System.getenv("JAVA_HOME"),
                () -> DoctorFacts.Extensions.found(ProjectExtensionArtifacts.of(project).size(), classPath()));
    }

    /**
     * The file of each resolved artifact that has one, in resolution order: where {@link KeyDoctor} looks for the
     * descriptions of the configuration keys. A module of the reactor resolves to its directory of classes.
     */
    private List<Path> classPath() {
        return project.getArtifacts().stream()
                .map(Artifact::getFile)
                .filter(Objects::nonNull)
                .map(File::toPath)
                .toList();
    }

    @Override
    public void execute() throws MojoFailureException {
        Path basedir = project.getBasedir().toPath();
        if (fix) {
            try {
                DoctorReport.fix(basedir, runtimeVersion, new LogReporter(getLog()));
            } catch (IOException e) {
                throw new MojoFailureException("vidocq:doctor could not fix the project: " + e.getMessage(), e);
            }
        }
        // The diagnosis of vidocq doctor too (#779); only the log levels and the way to fix are the goal's.
        DoctorReport report = DoctorReport.of(basedir, context(),
                DoctorFacts.Extensions.found(ProjectExtensionArtifacts.of(project).size(), classPath()));
        for (Diagnostic d : report.diagnostics()) {
            Consumer<CharSequence> log = level(getLog(), d.status());
            log.accept(DoctorReport.line(d));
            if (DoctorReport.showsHint(d, verbose)) {
                log.accept("    ↳ " + d.hint());
            }
            if (DoctorReport.showsFix(d)) {
                log.accept("    ↳ Fix: mvn vidocq:doctor -Dvidocq.doctor.fix");
            }
        }
        Consumer<CharSequence> verdict = report.exitCode() == 0 ? getLog()::info : getLog()::error;
        verdict.accept("  " + report.verdict());
        getLog().info("  " + report.summaryLine());
        if (verbose) {
            getLog().info("  Minimum Java: " + Diagnostics.MINIMUM_JAVA_VERSION);
        }
        if (report.exitCode() != 0) {
            throw new MojoFailureException("vidocq:doctor: " + report.verdict());
        }
    }

    private static Consumer<CharSequence> level(Log log, Diagnostic.Status status) {
        return switch (status) {
            case OK -> log::info;
            case WARN -> log::warn;
            case FAIL -> log::error;
        };
    }
}
