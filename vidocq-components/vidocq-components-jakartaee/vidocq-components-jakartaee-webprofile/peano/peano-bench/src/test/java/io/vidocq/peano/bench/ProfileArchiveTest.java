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
package io.vidocq.peano.bench;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileArchiveTest {
    @TempDir Path temporary;

    @Test void archivedRecordingsContainOnlyCpuAndAllocationEvents() throws IOException {
        try (var paths=Files.walk(Path.of("results"))) {
            var recordings=paths.filter(path->path.toString().endsWith(".jfr")).toList();
            assertFalse(recordings.isEmpty(),"Expected archived optimization profiles");
            for (Path path:recordings) verify(path);
        }
    }

    @Test void rejectsAnUnapprovedEventWithoutDisplayingItsValues() throws Exception {
        Path file=temporary.resolve("unapproved.jfr");
        try (var recording=new Recording()) {
            recording.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            recording.start();
            Thread.sleep(2);
            recording.stop(); recording.dump(file);
        }
        assertThrows(AssertionError.class,()->verify(file));
    }

    private static void verify(Path path) throws IOException {
        var allowed=Set.of("jdk.ExecutionSample","jdk.ObjectAllocationSample");
        try (var recording=new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                String type=recording.readEvent().getEventType().getName();
                assertTrue(allowed.contains(type),()->"Unexpected archived profile event type: "+type);
            }
        }
    }
}
