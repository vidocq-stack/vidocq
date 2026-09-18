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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.core.report.CoreSections.Extension;
import io.vidocq.runtime.core.report.CoreSections.LayerModule;
import io.vidocq.runtime.core.report.Section.Cells;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sections the core writes, from facts already read. */
class CoreSectionsTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();
    private static final DisplayPaths PATHS = new DisplayPaths(absolute("home/dev/work"), absolute("home/dev"));

    // ------------------------------------------------------------------------------------------- layer

    @Test
    void aLayerFromTheAppPathNamesItsArchivesWithoutTheirDirectoriesInTheSummary() {
        Section layer = CoreSections.layer("vidocq.app.path", List.of(file("opt/acme/app"), file("opt/acme/lib.jar")),
                List.of(new LayerModule("com.acme.app", file("home/dev/work/app/target/classes"), "directory"),
                        new LayerModule("com.acme.lib", file("home/dev/.m2/repository/com/acme/lib/1.0/lib-1.0.jar"), "jar"),
                        new LayerModule("com.acme.image", null, "jrt")),
                "none", PATHS);

        assertEquals("3 modules (vidocq.app.path=" + local(".../app") + File.pathSeparator + local(".../lib.jar") + ")",
                layer.summary());
        assertEquals("3 modules (vidocq.app.path=" + file("opt/acme/app") + File.pathSeparator + file("opt/acme/lib.jar")
                + ")", layer.headline());
        assertEquals(List.of(
                new Cells(List.of("com.acme.app", local("app/target/classes"), "directory")),
                new Cells(List.of("com.acme.lib", local("~/.m2/.../lib-1.0.jar"), "jar")),
                new Cells(List.of("com.acme.image", "-", "jrt")),
                new Row("weaving", "none")), layer.lines());
    }

    @Test
    void aLayerFoundInTheBootLayerSaysSo() {
        Section layer = CoreSections.layer("boot-layer detection from com.acme.app", List.of(),
                List.of(new LayerModule("com.acme.app", file("home/dev/work/app/target/classes"), "directory")), null,
                PATHS);

        assertEquals("1 module (boot-layer detection from com.acme.app)", layer.summary());
        assertEquals(layer.summary(), layer.headline());
    }

    @Test
    void withoutAnApplicationLayerTheWeavingIsStillShown() {
        Section layer = CoreSections.layer(null, List.of(), List.of(), "none", PATHS);

        assertEquals("no application layer", layer.summary());
        assertEquals(List.of(new Row("weaving", "none")), layer.lines());
    }

    @Test
    void theSummaryReportPrintsNoAbsolutePath() {
        Section layer = CoreSections.layer("vidocq.app.path", List.of(file("home/dev/work/app")),
                List.of(new LayerModule("com.acme.app", file("home/dev/work/app"), "directory")), "none", PATHS);
        StartupReport report = new StartupReport(LaunchMode.PROD, "auto: no dev or test signal", Verbosity.SUMMARY,
                List.of(), List.of(layer), List.of(), null, null);

        String text = StartupReportRenderer.render(report);

        assertFalse(text.contains(file("home")), text);
        assertTrue(text.contains("  layer       1 module (vidocq.app.path=" + local(".../app") + ")"), text);
    }

    @Test
    void theWeavingSaysWhoWeavesWhichBeans() {
        assertEquals("none", CoreSections.weaving(List.of(), false, false));
        assertEquals("failed (VAUBAN-009)", CoreSections.weaving(List.of(), true, false));
        assertEquals("Vauban class loader, 1 planned bean: McpToolDiscovery",
                CoreSections.weaving(List.of("io.acme.McpToolDiscovery"), false, true));
        assertEquals("load-time weaving agent, 2 planned beans: Tools, Prompts$Inner",
                CoreSections.weaving(List.of("io.acme.Tools", "io.acme.Prompts$Inner"), false, false));
    }

    // ------------------------------------------------------------------------------------ configuration

    @Test
    void theConfigurationNamesItsSourcesItsProviderAndTheAudit() {
        Section configuration = CoreSections.configuration(List.of("MicroProfile 400"),
                List.of("io.vidocq.ravel.RavelConfigSourceProvider"), List.of("vidocq.startup.*"));

        assertNull(configuration.summary(), "detailed only");
        assertEquals(List.of(
                new Items("sources", List.of("MicroProfile 400")),
                new Row("provider", "io.vidocq.ravel.RavelConfigSourceProvider (replaces the native sources)"),
                new Items("audited", List.of("vidocq.startup.*"))), configuration.lines());
        assertEquals(2, CoreSections.configuration(List.of(), List.of(), List.of()).lines().size(), "no provider row");
    }

    // --------------------------------------------------------------------------------------- extensions

    @Test
    void theExtensionsAreNamedInTheSummaryAndDetailedOnePerLine() {
        Section extensions = CoreSections.extensions(List.of(
                new Extension("chappe-engine", 100, "io.vidocq.chappe (boot layer)", List.of("vidocq.chappe.*"), 3_400_000,
                        false),
                new Extension("rest-cassini", 500, "class path", List.of(), -1, true),
                new Extension("chappe-bootstrap", 10000, "class path", List.of(), -1, false)));

        assertEquals("chappe-engine, rest-cassini, chappe-bootstrap", extensions.summary());
        assertNull(extensions.headline());
        assertEquals(List.of(
                new Cells(List.of("100", "chappe-engine", "onStart 3 ms", "io.vidocq.chappe (boot layer)", "vidocq.chappe.*")),
                new Cells(List.of("500", "rest-cassini", "onStart failed", "class path", "")),
                new Cells(List.of("10000", "chappe-bootstrap", "not started", "class path", ""))), extensions.lines());
    }

    @Test
    void noExtensionIsNone() {
        Section extensions = CoreSections.extensions(List.of());

        assertEquals("none", extensions.summary());
        assertEquals("none", extensions.headline());
    }

    // ------------------------------------------------------------------------------------------- header

    @Test
    void theLaunchReasonSaysWhetherTheModeWasDetected() {
        assertEquals("vidocq.launch.mode", CoreSections.launchReason("vidocq.launch.mode", true));
        assertEquals("auto: IntelliJ agent", CoreSections.launchReason("IntelliJ agent", false));
        assertEquals("auto: launch not read", CoreSections.launchReason(null, false));
    }

    @Test
    void theRuntimeIsTheVersionAndTheJvm() {
        assertEquals("0.4.0-SNAPSHOT on Java 25+36-LTS", CoreSections.runtime("0.4.0-SNAPSHOT", "25+36-LTS"));
        assertEquals("version unknown on Java 25", CoreSections.runtime(null, "25"));
    }

    private static Path absolute(String unixPath) {
        return ROOT.resolve(local(unixPath));
    }

    private static String file(String unixPath) {
        return absolute(unixPath).toString();
    }

    private static String local(String unixPath) {
        return unixPath.replace("/", File.separator);
    }
}
