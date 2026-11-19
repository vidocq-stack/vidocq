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
package io.vidocq.runtime.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliVersionTest {

    private static final String SEMVER = "\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?";

    @Test
    void cliVersionIsResolvedFromBuild() {
        String v = Version.cli();
        assertNotNull(v);
        assertFalse(v.isBlank());
        assertFalse(v.contains("${"), "cli version must be filtered by the build, got: " + v);
        assertTrue(v.matches(SEMVER), "cli version must be semver, got: " + v);
    }

    @Test
    void runtimeVersionIsResolvedFromBuild() {
        String v = Version.runtime();
        assertNotNull(v);
        assertFalse(v.isBlank());
        assertFalse(v.contains("${"), "runtime version must be filtered by the build, got: " + v);
        assertTrue(v.matches(SEMVER), "runtime version must be semver, got: " + v);
    }

    @Test
    void buildTimestampIsResolvedFromBuild() {
        String ts = Version.buildTimestamp();
        assertFalse(ts.contains("${"), "build timestamp must be filtered by the build, got: " + ts);
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                "build timestamp must be ISO-8601 UTC, got: " + ts);
    }

    @Test
    void snapshotDisplaysItsBuildTimestamp() {
        assertEquals("0.4.0-SNAPSHOT (built 2026-10-06T12:34:56Z)",
                Version.display("0.4.0-SNAPSHOT", "2026-10-06T12:34:56Z"));
    }

    @Test
    void releaseDisplaysTheVersionAlone() {
        assertEquals("0.4.0", Version.display("0.4.0", "2026-10-06T12:34:56Z"));
    }

    @Test
    void snapshotWithoutAResolvedTimestampDisplaysTheVersionAlone() {
        assertEquals("0.4.0-SNAPSHOT", Version.display("0.4.0-SNAPSHOT", ""));
        assertEquals("0.4.0-SNAPSHOT", Version.display("0.4.0-SNAPSHOT", "${build.timestamp}"));
    }
}
