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
package io.vidocq.runtime.cli.update;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePlanTest {

    private static final String CENTRAL = "https://central/io/vidocq/runtime/vidocq-runtime-cli";
    private static final String SNAPSHOTS = "https://snapshots/io/vidocq/runtime/vidocq-runtime-cli";

    private final Map<String, String> remote = new HashMap<>();

    private UpdatePlan.Decision plan(String current, String marker) {
        return plan(current, marker, "");
    }

    private UpdatePlan.Decision plan(String current, String marker, String builtAt) {
        return UpdatePlan.plan(current, Optional.ofNullable(marker), builtAt,
                url -> Optional.ofNullable(remote.get(url)), CENTRAL, SNAPSHOTS);
    }

    private void release(String latest) {
        remote.put(CENTRAL + "/maven-metadata.xml",
                "<metadata><versioning><latest>" + latest + "</latest><release>" + latest
                        + "</release></versioning></metadata>");
    }

    private void snapshots(List<String> lines, String line, String zipValue) {
        StringBuilder versions = new StringBuilder();
        lines.forEach(v -> versions.append("<version>").append(v).append("</version>"));
        remote.put(SNAPSHOTS + "/maven-metadata.xml",
                "<metadata><versioning><versions>" + versions + "</versions></versioning></metadata>");
        remote.put(SNAPSHOTS + "/" + line + "/maven-metadata.xml", """
                <metadata><versioning><snapshotVersions>
                  <snapshotVersion><extension>jar</extension><value>%1$s</value></snapshotVersion>
                  <snapshotVersion>
                    <classifier>cli</classifier>
                    <extension>zip</extension>
                    <value>%1$s</value>
                    <updated>20261006103352</updated>
                  </snapshotVersion>
                </snapshotVersions></versioning></metadata>
                """.formatted(zipValue));
    }

    @Test
    void releaseMovesToANewerRelease() {
        release("0.4.0");

        var update = assertInstanceOf(UpdatePlan.Update.class, plan("0.3.0", null));
        assertEquals("0.4.0", update.version());
        assertEquals(CENTRAL + "/0.4.0/vidocq-runtime-cli-0.4.0-cli.zip", update.zipUrl());
    }

    @Test
    void releaseComparesVersionsNumerically() {
        release("0.9.0");

        assertInstanceOf(UpdatePlan.UpToDate.class, plan("0.10.0", null));
        assertInstanceOf(UpdatePlan.UpToDate.class, plan("0.9.0", null));
    }

    @Test
    void snapshotIsUpToDateWhenItsInstalledBuildIsTheLatest() {
        snapshots(List.of("0.3.0-SNAPSHOT", "0.4.0-SNAPSHOT"), "0.4.0-SNAPSHOT", "0.4.0-20261006.103352-63");

        assertInstanceOf(UpdatePlan.UpToDate.class, plan("0.4.0-SNAPSHOT", "0.4.0-20261006.103352-63"));
    }

    @Test
    void snapshotTakesANewerBuildOfItsLine() {
        snapshots(List.of("0.4.0-SNAPSHOT"), "0.4.0-SNAPSHOT", "0.4.0-20261006.103352-63");

        var update = assertInstanceOf(UpdatePlan.Update.class, plan("0.4.0-SNAPSHOT", "0.4.0-20261001.212424-62"));
        assertEquals("0.4.0-SNAPSHOT", update.version());
        assertEquals(SNAPSHOTS + "/0.4.0-SNAPSHOT/vidocq-runtime-cli-0.4.0-20261006.103352-63-cli.zip",
                update.zipUrl());
        assertEquals(Optional.of("0.4.0-20261006.103352-63"), update.build());
    }

    @Test
    void snapshotWithoutAnInstalledBuildMarkerTakesAPublishedBuildNewerThanItself() {
        snapshots(List.of("0.4.0-SNAPSHOT"), "0.4.0-SNAPSHOT", "0.4.0-20261006.103352-63");

        assertInstanceOf(UpdatePlan.Update.class, plan("0.4.0-SNAPSHOT", null, "2026-10-06T09:00:00Z"));
        assertInstanceOf(UpdatePlan.Update.class, plan("0.4.0-SNAPSHOT", null, ""));
    }

    @Test
    void snapshotBuiltLocallyAfterThePublishedBuildIsNotDowngraded() {
        snapshots(List.of("0.4.0-SNAPSHOT"), "0.4.0-SNAPSHOT", "0.4.0-20261006.103352-63");

        assertInstanceOf(UpdatePlan.UpToDate.class, plan("0.4.0-SNAPSHOT", null, "2026-10-06T10:56:47Z"));
    }

    @Test
    void snapshotMovesToAHigherSnapshotLine() {
        snapshots(List.of("0.4.0-SNAPSHOT", "0.10.0-SNAPSHOT", "0.5.0-SNAPSHOT"), "0.10.0-SNAPSHOT",
                "0.10.0-20261107.080000-1");

        var update = assertInstanceOf(UpdatePlan.Update.class, plan("0.4.0-SNAPSHOT", "anything"));
        assertEquals("0.10.0-SNAPSHOT", update.version());
    }

    @Test
    void unreachableRepositoryIsReported() {
        var unavailable = assertInstanceOf(UpdatePlan.Unavailable.class, plan("0.3.0", null));
        assertTrue(unavailable.reason().contains(CENTRAL + "/maven-metadata.xml"), unavailable.reason());
    }

    @Test
    void snapshotLineWithoutACliZipIsReported() {
        snapshots(List.of("0.4.0-SNAPSHOT"), "0.4.0-SNAPSHOT", "x");
        remote.put(SNAPSHOTS + "/0.4.0-SNAPSHOT/maven-metadata.xml", "<metadata/>");

        assertInstanceOf(UpdatePlan.Unavailable.class, plan("0.4.0-SNAPSHOT", null));
    }
}
