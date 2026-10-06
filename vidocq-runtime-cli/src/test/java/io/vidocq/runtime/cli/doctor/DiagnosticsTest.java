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
package io.vidocq.runtime.cli.doctor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsTest {

    /** A context where every check should pass. */
    private static DoctorContext healthy() {
        return new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION,           // javaFeatureVersion
                Diagnostics.MINIMUM_JAVA_VERSION + ".0.1",  // javaVersionString
                Diagnostics.MINIMUM_JAVA_VERSION,           // minimumJavaVersion
                "/opt/jdk",                                 // javaHome
                true,                                       // javaHomeIsDirectory
                true,                                       // mavenWrapperPresent
                true,                                       // pomPresent
                true,                                       // vidocqProject
                3);                                         // extensionCount
    }

    private static Map<String, Diagnostic> byName(List<Diagnostic> diagnostics) {
        return diagnostics.stream().collect(Collectors.toMap(Diagnostic::name, Function.identity()));
    }

    @Test
    void healthyContextPassesEveryCheck() {
        List<Diagnostic> diagnostics = Diagnostics.run(healthy());

        assertEquals(6, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.status() == Diagnostic.Status.OK));
        assertEquals(0, Diagnostics.exitCode(diagnostics));
    }

    @Test
    void okDiagnosticsCarryNoHint() {
        Diagnostics.run(healthy())
                .forEach(d -> assertNull(d.hint(), d.name() + " should have no hint when OK"));
    }

    @Test
    void oldJavaFailsAndDrivesExitCode() {
        DoctorContext ctx = new DoctorContext(
                21, "21.0.5", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 3);

        Diagnostic java = byName(Diagnostics.run(ctx)).get("Java version");
        assertEquals(Diagnostic.Status.FAIL, java.status());
        assertNotNull(java.hint());
        assertEquals(1, Diagnostics.exitCode(Diagnostics.run(ctx)));
    }

    @Test
    void missingJavaHomeWarnsButDoesNotFail() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                null, false, true, true, true, 3);

        Diagnostic javaHome = byName(Diagnostics.run(ctx)).get("JAVA_HOME");
        assertEquals(Diagnostic.Status.WARN, javaHome.status());
        assertEquals(0, Diagnostics.exitCode(Diagnostics.run(ctx)));
    }

    @Test
    void javaHomePointingAtNonDirectoryWarns() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/no/such/jdk", false, true, true, true, 3);

        assertEquals(Diagnostic.Status.WARN, byName(Diagnostics.run(ctx)).get("JAVA_HOME").status());
    }

    @Test
    void absentMavenWrapperWarns() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, false, true, true, 3);

        assertEquals(Diagnostic.Status.WARN, byName(Diagnostics.run(ctx)).get("Maven wrapper").status());
    }

    @Test
    void missingPomWarnsOnProject() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, false, false, 3);

        Diagnostic project = byName(Diagnostics.run(ctx)).get("Vidocq project");
        assertEquals(Diagnostic.Status.WARN, project.status());
        assertTrue(project.detail().contains("no pom.xml"));
    }

    @Test
    void nonVidocqPomWarnsOnProject() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, false, 3);

        assertEquals(Diagnostic.Status.WARN, byName(Diagnostics.run(ctx)).get("Vidocq project").status());
    }

    @Test
    void zeroExtensionsWarns() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 0);

        assertEquals(Diagnostic.Status.WARN, byName(Diagnostics.run(ctx)).get("Extensions").status());
    }

    @Test
    void extensionCountIsPluralisedCorrectly() {
        Map<String, Diagnostic> single = byName(Diagnostics.run(new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 1)));
        assertTrue(single.get("Extensions").detail().contains("1 extension in"));

        Map<String, Diagnostic> many = byName(Diagnostics.run(healthy()));
        assertTrue(many.get("Extensions").detail().contains("3 extensions in"));
    }

    @Test
    void zeroExtensionsHintNamesARealCatalogId() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 0);

        assertTrue(byName(Diagnostics.run(ctx)).get("Extensions").hint()
                .contains("vidocq extension add cassini-rest"));
    }

    @Test
    void failedDependencyResolutionWarnsWithTheReason() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 0, false, List.of(),
                "Maven exited with code 1");

        Diagnostic extensions = byName(Diagnostics.run(ctx)).get("Extensions");
        assertEquals(Diagnostic.Status.WARN, extensions.status());
        assertTrue(extensions.detail().contains("Maven exited with code 1"));
    }

    @Test
    void warningsAloneDoNotFailExitCode() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                null, false, false, false, false, 0);

        List<Diagnostic> diagnostics = Diagnostics.run(ctx);
        assertTrue(diagnostics.stream().noneMatch(d -> d.status() == Diagnostic.Status.FAIL));
        assertEquals(0, Diagnostics.exitCode(diagnostics));
    }

    @Test
    void summarizeTalliesEachStatus() {
        Diagnostics.Summary healthy = Diagnostics.summarize(Diagnostics.run(healthy()));
        assertEquals(6, healthy.ok());
        assertEquals(0, healthy.warn());
        assertEquals(0, healthy.fail());
        assertEquals(6, healthy.total());

        // Old Java fails + JAVA_HOME unset/mvnw/pom/extensions all warn; config absent → OK.
        DoctorContext mixed = new DoctorContext(
                21, "21", Diagnostics.MINIMUM_JAVA_VERSION,
                null, false, false, false, false, 0);
        Diagnostics.Summary s = Diagnostics.summarize(Diagnostics.run(mixed));
        assertEquals(1, s.fail());
        assertEquals(4, s.warn());
        assertEquals(1, s.ok());
        assertEquals(6, s.total());
    }

    @Test
    void absentConfigIsOk() {
        Diagnostic config = byName(Diagnostics.run(healthy())).get("Config");
        assertEquals(Diagnostic.Status.OK, config.status());
        assertTrue(config.detail().contains("defaults apply"));
    }

    @Test
    void knownConfigKeysAreOk() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 3,
                true, List.of("vidocq.http.port", "vidocq.dev.debug"));

        Diagnostic config = byName(Diagnostics.run(ctx)).get("Config");
        assertEquals(Diagnostic.Status.OK, config.status());
        assertTrue(config.detail().contains("all recognized"));
        assertEquals(0, Diagnostics.exitCode(Diagnostics.run(ctx)));
    }

    @Test
    void unknownConfigKeysWarnButDoNotFail() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 3,
                true, List.of("vidocq.http.port", "vidocq.htpp.port", "vidocq.bogus"));

        Diagnostic config = byName(Diagnostics.run(ctx)).get("Config");
        assertEquals(Diagnostic.Status.WARN, config.status());
        assertTrue(config.detail().contains("vidocq.bogus"));
        assertTrue(config.detail().contains("vidocq.htpp.port"));
        assertNotNull(config.hint());
        assertEquals(0, Diagnostics.exitCode(Diagnostics.run(ctx)));
    }

    @Test
    void nonVidocqKeysNeverWarn() {
        DoctorContext ctx = new DoctorContext(
                Diagnostics.MINIMUM_JAVA_VERSION, "25", Diagnostics.MINIMUM_JAVA_VERSION,
                "/opt/jdk", true, true, true, true, 3,
                true, List.of("my.app.setting", "logging.level"));

        assertEquals(Diagnostic.Status.OK, byName(Diagnostics.run(ctx)).get("Config").status());
    }
}
