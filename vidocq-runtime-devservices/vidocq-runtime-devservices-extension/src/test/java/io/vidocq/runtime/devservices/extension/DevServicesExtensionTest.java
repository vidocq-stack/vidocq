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

import io.vidocq.runtime.spi.report.Verbosity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review Focus 1 (global constraints): a file left in {@code target/} by a killed host must never be picked up by a
 * later plain run that never set {@value DevServicesExtension#STATE_PROPERTY}. The extension only reads the path the
 * property gives it — it never scans {@code target/} itself.
 */
class DevServicesExtensionTest {

    @Test
    void anUnreadableStateFileIsReportedWithItsPath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vidocq-dev-services.json");
        Files.writeString(file, "{\"host\":");

        String previous = System.getProperty(DevServicesExtension.STATE_PROPERTY);
        System.setProperty(DevServicesExtension.STATE_PROPERTY, file.toString());
        try {
            DevServicesExtension extension = new DevServicesExtension();
            extension.onStart(null);

            RecordingSection section = new RecordingSection();
            extension.contribute(new FakeReportContext(Verbosity.DETAILED), section);
            assertEquals(List.of("VIDOCQ-DEVS-001"), section.codes());
            String message = section.anomalies.getFirst().message();
            assertTrue(message.contains(file.toString()), message);
        } finally {
            if (previous == null) {
                System.clearProperty(DevServicesExtension.STATE_PROPERTY);
            } else {
                System.setProperty(DevServicesExtension.STATE_PROPERTY, previous);
            }
        }
    }

    @Test
    void aStaleFileWithNoStatePropertyIsNeverScannedFor(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("vidocq-dev-services.json"), StateReaderTest.JSON);

        String previous = System.getProperty(DevServicesExtension.STATE_PROPERTY);
        System.clearProperty(DevServicesExtension.STATE_PROPERTY);
        try {
            DevServicesExtension extension = new DevServicesExtension();
            extension.onStart(null);

            RecordingSection section = new RecordingSection();
            extension.contribute(new FakeReportContext(Verbosity.DETAILED), section);
            assertEquals("no dev service: not started by vidocq:dev, vidocq:run or the test launcher",
                    section.summary);
            assertTrue(section.anomalies.isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty(DevServicesExtension.STATE_PROPERTY);
            } else {
                System.setProperty(DevServicesExtension.STATE_PROPERTY, previous);
            }
        }
    }
}
