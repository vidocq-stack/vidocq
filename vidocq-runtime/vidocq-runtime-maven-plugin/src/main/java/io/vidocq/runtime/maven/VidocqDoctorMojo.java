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
import io.vidocq.runtime.tooling.ProjectDoctor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * first repairs what they report as fixable (Vidocq/vidocq#339).</p>
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
                () -> DoctorFacts.Extensions.found(ProjectExtensionArtifacts.of(project).size()));
    }

    @Override
    public void execute() throws MojoFailureException {
        Path basedir = project.getBasedir().toPath();
        if (fix) {
            try {
                if (!ProjectDoctor.fix(basedir, runtimeVersion, new LogReporter(getLog()))) {
                    getLog().info("vidocq:doctor: nothing to fix.");
                }
            } catch (IOException e) {
                throw new MojoFailureException("vidocq:doctor could not fix the project: " + e.getMessage(), e);
            }
        }
        List<Diagnostic> diagnostics = new ArrayList<>(Diagnostics.run(context()));
        diagnostics.addAll(ProjectDoctor.check(ProjectDoctor.read(basedir, DoctorFacts::portFree)));
        for (Diagnostic d : diagnostics) {
            Consumer<CharSequence> log = level(getLog(), d.status());
            log.accept(String.format("%s %-17s %s", symbol(d.status()), d.name(), d.detail()));
            if (d.hint() != null && (verbose || d.status() != Diagnostic.Status.OK)) {
                log.accept("    ↳ " + d.hint());
            }
            if (d.fixable() && d.status() != Diagnostic.Status.OK) {
                log.accept("    ↳ Fix: mvn vidocq:doctor -Dvidocq.doctor.fix");
            }
        }
        Diagnostics.Summary summary = Diagnostics.summarize(diagnostics);
        getLog().info("  " + summary.total() + " checks — " + summary.ok() + " ok, " + summary.warn()
                + " warning(s), " + summary.fail() + " failed");
        if (Diagnostics.exitCode(diagnostics) != 0) {
            throw new MojoFailureException("vidocq:doctor: one or more checks failed — see the hints above.");
        }
    }

    private static Consumer<CharSequence> level(Log log, Diagnostic.Status status) {
        return switch (status) {
            case OK -> log::info;
            case WARN -> log::warn;
            case FAIL -> log::error;
        };
    }

    private static String symbol(Diagnostic.Status status) {
        return switch (status) {
            case OK -> "✔";
            case WARN -> "⚠";
            case FAIL -> "✘";
        };
    }
}
