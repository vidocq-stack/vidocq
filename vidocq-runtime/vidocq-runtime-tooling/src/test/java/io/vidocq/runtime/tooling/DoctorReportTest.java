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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one diagnosis of {@code vidocq doctor} and {@code vidocq:doctor} (Vidocq/vidocq#779): the same checks in the same
 * order, the same lines, the same summary; each tool only styles them and names its own way to fix.
 */
class DoctorReportTest {

    @TempDir
    Path dir;

    @Test
    void theChecksOfTheFactsThenOfTheSourcesThenOfTheKeys() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project><parent><groupId>io.vidocq.runtime</groupId></parent></project>");

        DoctorReport report = DoctorReport.of(dir, null, () -> DoctorFacts.Extensions.found(2));

        List<String> names = report.diagnostics().stream().map(Diagnostic::name).toList();
        assertEquals("Java version", names.getFirst());
        assertEquals(List.of("Key descriptions", "Config values"), names.subList(names.size() - 2, names.size()));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.name().equals("Extensions")
                && d.detail().equals("2 extensions in the runtime dependencies")), report.diagnostics().toString());
    }

    @Test
    void theExtensionsAreAskedForAVidocqProjectOnly() {
        DoctorReport report = DoctorReport.of(dir, null, () -> {
            throw new AssertionError("no pom.xml: nothing to resolve");
        });

        assertFalse(report.diagnostics().isEmpty());
    }

    @Test
    void theSummaryAndTheVerdict() {
        DoctorReport passed = new DoctorReport(List.of(Diagnostic.ok("A", "fine"), Diagnostic.warn("B", "hm", "do")));
        DoctorReport failed = new DoctorReport(List.of(Diagnostic.fail("C", "broken", "repair")));

        assertEquals("2 checks — 1 ok, 1 warning(s), 0 failed", passed.summaryLine());
        assertEquals(0, passed.exitCode());
        assertEquals("No blocking issues found.", passed.verdict());
        assertEquals("1 checks — 0 ok, 0 warning(s), 1 failed", failed.summaryLine());
        assertEquals(1, failed.exitCode());
        assertEquals("One or more checks failed — see the hints above.", failed.verdict());
    }

    @Test
    void aLineGivesTheSymbolTheNameAndTheDetail() {
        assertEquals("✔ Java version      Java 25", DoctorReport.line(Diagnostic.ok("Java version", "Java 25")));
        assertEquals("⚠ JAVA_HOME         not set", DoctorReport.line(Diagnostic.warn("JAVA_HOME", "not set", "Set")));
        assertEquals("✘ X                 y", DoctorReport.line(Diagnostic.fail("X", "y", "z")));
        assertEquals("✘", DoctorReport.symbol(Diagnostic.Status.FAIL));
    }

    @Test
    void theHintOfACheckThatPassedShowsWhenVerboseOnly() {
        Diagnostic ok = new Diagnostic("A", Diagnostic.Status.OK, "fine", "more");
        Diagnostic warn = Diagnostic.warn("B", "hm", "do");

        assertFalse(DoctorReport.showsHint(ok, false));
        assertTrue(DoctorReport.showsHint(ok, true));
        assertTrue(DoctorReport.showsHint(warn, false));
        assertFalse(DoctorReport.showsHint(Diagnostic.ok("C", "no hint"), true));
    }

    @Test
    void theFixShowsForAFixableCheckThatDidNotPass() {
        Diagnostic fixable = Diagnostic.warn("Maven wrapper", "missing", "write it").asFixable();

        assertTrue(DoctorReport.showsFix(fixable));
        assertFalse(DoctorReport.showsFix(Diagnostic.warn("B", "hm", "do")));
        assertFalse(DoctorReport.showsFix(new Diagnostic("A", Diagnostic.Status.OK, "fine", null).asFixable()));
    }

    @Test
    void theFixSaysWhenThereIsNothingToFix() throws Exception {
        RecordingReporter reporter = new RecordingReporter();

        assertFalse(DoctorReport.fix(dir, "1.2.3", reporter));

        assertEquals(List.of("note: Nothing to fix."), reporter.lines);
    }

    @Test
    void theFixRepairsThroughTheProjectDoctor() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project><parent><groupId>io.vidocq.runtime</groupId></parent></project>");
        RecordingReporter reporter = new RecordingReporter();

        assertTrue(DoctorReport.fix(dir, "1.2.3", reporter));

        assertTrue(Files.isRegularFile(dir.resolve("mvnw")));
        assertTrue(reporter.lines.stream().noneMatch(l -> l.contains("Nothing to fix")), reporter.lines.toString());
    }
}
