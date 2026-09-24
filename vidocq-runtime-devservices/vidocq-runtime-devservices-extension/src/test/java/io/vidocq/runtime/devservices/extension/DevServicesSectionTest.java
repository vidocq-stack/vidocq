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
package io.vidocq.runtime.devservices.extension;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServicesSectionTest {

    @Test
    void summarisesEachServiceAndTheHost() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(StateReader.parse(StateReaderTest.JSON), null,
                new FakeReportContext(Verbosity.DETAILED), section);
        assertEquals("1 service: postgres (postgres:16-alpine at localhost:54321) — vidocq:dev", section.summary);
        assertEquals("postgres:16-alpine, default localhost:54321", section.rows.get("postgres"));
        assertEquals(Boolean.TRUE, section.secrets.get("postgres vidocq.pool.password"));
        assertEquals("jdbc:postgresql://localhost:54321/vidocq", section.rows.get("postgres vidocq.pool.url"));
        assertEquals("2026-09-24T10:12:03Z by vidocq:dev", section.rows.get("started"));
    }

    @Test
    void noHostMeansNoDevServiceAndNoAnomaly() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(DevServicesSnapshot.NONE, null, new FakeReportContext(Verbosity.DETAILED), section);
        assertEquals("no dev service: not started by vidocq:dev, vidocq:run or the test launcher", section.summary);
        assertTrue(section.anomalies.isEmpty());
    }

    @Test
    void anUnreadableFileIsAnAnomalyAndTheBootGoesOn() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(DevServicesSnapshot.NONE, "IllegalArgumentException",
                new FakeReportContext(Verbosity.DETAILED), section);
        assertEquals(List.of("VIDOCQ-DEVS-001"), section.codes());
    }

    @Test
    void valuesAreShownInADevLaunchOnly() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(StateReader.parse(StateReaderTest.JSON), null,
                new FakeReportContext(Verbosity.DETAILED, LaunchMode.TEST), section);
        assertNull(section.rows.get("postgres vidocq.pool.url"), "keys only outside dev");
        assertTrue(section.lists.get("postgres keys").contains("vidocq.pool.url"));
    }
}
