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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the report writes a file: never a directory in {@code summary}, relative or under {@code ~} in {@code detailed}. */
class DisplayPathsTest {

    /** The root of the file system of this JVM, so that the absolute paths below are absolute everywhere. */
    private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();
    private static final DisplayPaths PATHS = new DisplayPaths(absolute("home/dev/work"), absolute("home/dev"));

    @Test
    void theSummaryKeepsTheFileNameOnly() {
        assertEquals(local(".../app"), PATHS.summary(file("opt/acme/app")));
        assertEquals(local(".../app.jar"), PATHS.summary(file("home/dev/work/app.jar")));
        assertEquals("app", PATHS.summary("app"));
    }

    @Test
    void theDetailedReportWritesAFileUnderTheWorkingDirectoryRelativeToIt() {
        assertEquals(local("mcp-time-server/target/classes"),
                PATHS.detailed(file("home/dev/work/mcp-time-server/target/classes")));
        assertEquals(".", PATHS.detailed(file("home/dev/work")));
    }

    @Test
    void theDetailedReportWritesAFileUnderTheHomeDirectoryAfterATilde() {
        assertEquals(local("~/.m2/.../langchain4j-cdi-mcp-server-1.4.0-SNAPSHOT.jar"), PATHS.detailed(file(
                "home/dev/.m2/repository/dev/langchain4j/cdi/mcp/1.4.0-SNAPSHOT/langchain4j-cdi-mcp-server-1.4.0-SNAPSHOT.jar")));
        assertEquals(local("~/other/x.jar"), PATHS.detailed(file("home/dev/work/../other/x.jar")));
        assertEquals("~", PATHS.detailed(file("home/dev")));
    }

    @Test
    void aLongPathKeepsItsFirstTwoNamesAndItsLast() {
        assertEquals(ROOT + local("opt/vidocq/.../x.jar"), PATHS.detailed(file("opt/vidocq/app/lib/deep/x.jar")));
        assertEquals(ROOT + local("opt/app/lib/x.jar"), PATHS.detailed(file("opt/app/lib/x.jar")));
        assertEquals(local("app/target/classes"), PATHS.detailed(local("app/target/classes")));
    }

    @Test
    void aRootIsNeverTakenForTheWorkingDirectory() {
        assertEquals(ROOT + local("opt/x.jar"), new DisplayPaths(ROOT, null).detailed(file("opt/x.jar")));
    }

    @Test
    void aNameThatEmbedsAPathIsScrubbed() {
        assertEquals("ExternalFile(" + local("config/vidocq.properties") + ")",
                PATHS.scrub("ExternalFile(" + file("home/dev/work/config/vidocq.properties") + ")"));
        assertEquals("file:" + local("~/conf/a.properties"), PATHS.scrub("file:" + file("home/dev/conf/a.properties")));
        assertEquals("PropertiesFile", PATHS.scrub("PropertiesFile"));
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
