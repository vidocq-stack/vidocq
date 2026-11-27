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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The diagnosis of {@code vidocq doctor} and {@code vidocq:doctor}, one implementation for both (Vidocq/vidocq#779):
 * the checks of the facts {@link DoctorFacts} gathers, then those of the project's sources ({@link ProjectDoctor}),
 * then those of the descriptions of the configuration keys ({@link KeyDoctor}), with the lines, the summary and the
 * verdict both print. A tool styles them its own way (the CLI's colours, the plugin's log levels) and names its own
 * command to fix.
 *
 * @param diagnostics the checks, in display order
 */
public record DoctorReport(List<Diagnostic> diagnostics) {

    /** What {@link #fix} says when it changed nothing. */
    public static final String NOTHING_TO_FIX = "Nothing to fix.";

    public DoctorReport {
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * The diagnosis of the project in {@code dir}, {@code javaHome} being the {@code JAVA_HOME} the tool sees (null
     * when unset). {@code extensions} is asked once at most, and only for a Vidocq project, as it may run Maven: the
     * checks of the keys then read the jars it found, else the project's files alone.
     */
    public static DoctorReport of(Path dir, String javaHome, Supplier<DoctorFacts.Extensions> extensions) {
        AtomicReference<DoctorFacts.Extensions> asked = new AtomicReference<>();
        DoctorContext context = DoctorFacts.gather(dir, javaHome, () -> {
            asked.set(extensions.get());
            return asked.get();
        });
        DoctorFacts.Extensions found = asked.get();
        return of(dir, context, found == null ? DoctorFacts.Extensions.found(0) : found);
    }

    /** The diagnosis of the project in {@code dir} from facts already gathered, and its extensions. */
    public static DoctorReport of(Path dir, DoctorContext context, DoctorFacts.Extensions extensions) {
        List<Diagnostic> diagnostics = new ArrayList<>(Diagnostics.run(context));
        diagnostics.addAll(ProjectDoctor.check(ProjectDoctor.read(dir, DoctorFacts::portFree)));
        diagnostics.addAll(KeyDoctor.check(KeyDoctor.read(dir, extensions)));
        return new DoctorReport(diagnostics);
    }

    /**
     * {@code --fix} / {@code -Dvidocq.doctor.fix}: repairs what the checks report as fixable in the project in
     * {@code dir}, before the diagnosis, through {@link ProjectDoctor#fix}, and says {@link #NOTHING_TO_FIX} when it
     * changed nothing.
     *
     * @return whether it changed a file
     */
    public static boolean fix(Path dir, String defaultRuntimeVersion, Reporter reporter) throws IOException {
        boolean changed = ProjectDoctor.fix(dir, defaultRuntimeVersion, reporter);
        if (!changed) {
            reporter.note(NOTHING_TO_FIX);
        }
        return changed;
    }

    /** {@code 1} when a check failed, else {@code 0}: a warning does not fail. */
    public int exitCode() {
        return Diagnostics.exitCode(diagnostics);
    }

    /** {@code N checks — a ok, b warning(s), c failed}. */
    public String summaryLine() {
        Diagnostics.Summary summary = Diagnostics.summarize(diagnostics);
        return summary.total() + " checks — " + summary.ok() + " ok, " + summary.warn() + " warning(s), "
                + summary.fail() + " failed";
    }

    /** The verdict after the checks. */
    public String verdict() {
        return exitCode() == 0 ? "No blocking issues found." : "One or more checks failed — see the hints above.";
    }

    /** The line of a check: its symbol, its name in a column, its detail. */
    public static String line(Diagnostic diagnostic) {
        return String.format("%s %-17s %s", symbol(diagnostic.status()), diagnostic.name(), diagnostic.detail());
    }

    /** {@code ✔}, {@code ⚠} or {@code ✘}. */
    public static String symbol(Diagnostic.Status status) {
        return switch (status) {
            case OK -> "✔";
            case WARN -> "⚠";
            case FAIL -> "✘";
        };
    }

    /** Whether the hint of {@code diagnostic} shows: always for a check that did not pass, with verbose otherwise. */
    public static boolean showsHint(Diagnostic diagnostic, boolean verbose) {
        return diagnostic.hint() != null && (verbose || diagnostic.status() != Diagnostic.Status.OK);
    }

    /** Whether the way to fix {@code diagnostic} shows: for a fixable check that did not pass. */
    public static boolean showsFix(Diagnostic diagnostic) {
        return diagnostic.fixable() && diagnostic.status() != Diagnostic.Status.OK;
    }
}
