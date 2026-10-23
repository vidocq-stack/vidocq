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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.function.Consumer;

/**
 * Checks the environment and the project, as {@code vidocq doctor} does: both diagnose the facts
 * {@link DoctorFacts} gathers with the same {@link Diagnostics} (Vidocq/vidocq#314). A failed check fails the build;
 * a warning does not.
 *
 * <pre>mvn vidocq:doctor [-Dvidocq.doctor.verbose]</pre>
 *
 * <p>Has no default phase. The extensions are those of the project's runtime dependencies, which Maven resolves
 * before the goal runs.</p>
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

    // package-private: a test feeds its own facts.
    DoctorContext context() {
        return DoctorFacts.gather(project.getBasedir().toPath(), System.getenv("JAVA_HOME"),
                () -> DoctorFacts.Extensions.found(ProjectExtensionArtifacts.of(project).size()));
    }

    @Override
    public void execute() throws MojoFailureException {
        List<Diagnostic> diagnostics = Diagnostics.run(context());
        for (Diagnostic d : diagnostics) {
            Consumer<CharSequence> log = level(getLog(), d.status());
            log.accept(String.format("%s %-16s %s", symbol(d.status()), d.name(), d.detail()));
            if (d.hint() != null && (verbose || d.status() != Diagnostic.Status.OK)) {
                log.accept("    ↳ " + d.hint());
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
