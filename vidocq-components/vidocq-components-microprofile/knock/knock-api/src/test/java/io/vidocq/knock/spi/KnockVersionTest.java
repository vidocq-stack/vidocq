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
package io.vidocq.knock.spi;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published 0.2.0 artifact reported a hardcoded "0.1.0-SNAPSHOT" — the
 * implementation version must be derived from the build, never maintained by hand.
 */
class KnockVersionTest {

    @Test
    void implementationVersionMatchesTheBuildVersion() {
        // project.version is injected by surefire (systemPropertyVariables).
        String buildVersion = System.getProperty("project.version");
        String reportedVersion = Knock.IMPLEMENTATION_VERSION;
        assertEquals(buildVersion, reportedVersion,
                "Knock.IMPLEMENTATION_VERSION must be the Maven build version, not a hardcoded constant");
    }

    @Test
    void implementationVersionIsUsable() {
        assertFalse(Knock.IMPLEMENTATION_VERSION.isBlank());
        assertFalse(Knock.IMPLEMENTATION_VERSION.contains("${"),
                "version.properties must be filtered by the build");
    }

    @Test
    void specVersionIsStable() {
        assertEquals("4.0", Knock.SPEC_VERSION);
    }

    @Test
    void aMissingVersionResourceReportsUnknown() {
        assertEquals("unknown", Knock.readVersion(null));
    }

    @Test
    void aVersionPropertyIsRead() {
        InputStream in = new ByteArrayInputStream("version=1.2.3\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("1.2.3", Knock.readVersion(in));
    }

    @Test
    void aResourceWithoutTheVersionKeyReportsUnknown() {
        InputStream in = new ByteArrayInputStream("other=1\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("unknown", Knock.readVersion(in));
    }

    @Test
    void anUnreadableVersionResourceReportsUnknownAndIsClosed() {
        var closed = new boolean[1];
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        assertEquals("unknown", Knock.readVersion(broken));
        assertTrue(closed[0], "the stream must be closed even when it cannot be read");
    }
}
