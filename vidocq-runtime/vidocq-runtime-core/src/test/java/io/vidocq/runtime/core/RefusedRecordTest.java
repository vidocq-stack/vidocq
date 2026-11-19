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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.report.StartupAnomalies;
import io.vidocq.runtime.core.report.StartupRecorder;
import io.vidocq.vauban.core.container.ExtensionsAtStart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a boot says, and does, when its build compatible extensions ran at start instead of loading the build's record. */
class RefusedRecordTest {

    private static final String LOGGER = "io.vidocq.runtime.core.RefusedRecord";

    private static final String NOTE = "the record of the frozen extensions (META-INF/vauban-frozen-extensions.properties)"
            + " was written for another deployment: 1 bean class differs from the build's: com.acme.Extra (added)";

    private static final ExtensionsAtStart FROZEN = new ExtensionsAtStart(true,
            List.of("com.acme.StaticExtension"), List.of("com.acme.StartCheckExtension"), null);

    private static final ExtensionsAtStart REFUSED = new ExtensionsAtStart(false, List.of(),
            List.of("com.acme.StaticExtension", "com.acme.StartCheckExtension"), NOTE);

    private static final ExtensionsAtStart NO_RECORD = new ExtensionsAtStart(false, List.of(),
            List.of("com.acme.StaticExtension"), null);

    private static final ExtensionsAtStart NO_EXTENSION = new ExtensionsAtStart(false, List.of(), List.of(), null);

    private final StartupRecorder recorder = new StartupRecorder();

    @Test
    void aStartOnTheRecordSaysNothing() {
        try (LogRecords logs = new LogRecords(LOGGER)) {
            RefusedRecord.report(FROZEN, false, false, recorder);

            assertEquals(List.of(), recorder.anomalies());
            assertEquals(List.of(), logs.messages(LOGGER, Level.INFO));
        }
    }

    @Test
    void aRefusedRecordInProductionIsAnAnomalyThatNamesWhatDiffers() {
        RefusedRecord.report(REFUSED, false, false, recorder);

        assertEquals(1, recorder.anomalies().size(), () -> recorder.anomalies().toString());
        var anomaly = recorder.anomalies().getFirst();
        assertEquals(StartupAnomalies.RECORD_REFUSED, anomaly.code());
        assertEquals("2 build compatible extensions ran at start: " + NOTE + ".", anomaly.message());
        assertTrue(anomaly.hint().contains("build it again"), anomaly.hint());
    }

    @Test
    void aRefusedRecordADevLaunchOrATestExpectsIsSaidAtInfo() {
        try (LogRecords logs = new LogRecords(LOGGER)) {
            RefusedRecord.report(REFUSED, true, false, recorder);

            assertEquals(List.of(), recorder.anomalies());
            assertEquals(List.of("2 build compatible extensions ran at start: " + NOTE + "."),
                    logs.messages(LOGGER, Level.INFO));
        }
    }

    @Test
    void noRecordAtAllSaysNothingUnlessItIsRequired() {
        RefusedRecord.report(NO_RECORD, false, false, recorder);
        RefusedRecord.report(NO_EXTENSION, false, true, recorder);

        assertEquals(List.of(), recorder.anomalies());
    }

    @Test
    void aRequiredRecordFailsTheStartWhenItWasRefused() {
        var failure = assertThrows(IllegalStateException.class,
                () -> RefusedRecord.report(REFUSED, true, true, recorder));

        assertEquals("[" + StartupAnomalies.RECORD_REFUSED + "] " + RefusedRecord.REQUIRE_KEY + " is true and 2 build"
                + " compatible extensions ran at start: " + NOTE + ".", failure.getMessage());
    }

    @Test
    void aRequiredRecordFailsTheStartWhenThereIsNone() {
        var failure = assertThrows(IllegalStateException.class,
                () -> RefusedRecord.report(NO_RECORD, false, true, recorder));

        assertTrue(failure.getMessage().contains("1 build compatible extension ran at start: no record of the build"
                + " is on the path"), failure.getMessage());
    }

    @Test
    void aRequiredRecordLetsAnExtensionThatRunsAgainAtStartBy() {
        RefusedRecord.report(FROZEN, false, true, recorder);

        assertEquals(List.of(), recorder.anomalies());
    }

    @Test
    void theKeyTakesTrueOrFalseAndReportsAnythingElse() {
        List<String> anomalies = new ArrayList<>();

        assertTrue(RefusedRecord.required("true", (code, message) -> anomalies.add(message)));
        assertTrue(RefusedRecord.required("TRUE", (code, message) -> anomalies.add(message)));
        assertFalse(RefusedRecord.required("false", (code, message) -> anomalies.add(message)));
        assertFalse(RefusedRecord.required(null, (code, message) -> anomalies.add(message)));
        assertEquals(List.of(), anomalies);

        assertFalse(RefusedRecord.required("yes", (code, message) -> anomalies.add(code + " " + message)));
        assertEquals(List.of(StartupAnomalies.INVALID_VALUE + " Invalid value 'yes' for " + RefusedRecord.REQUIRE_KEY
                + " (true, false): using false"), anomalies);
    }
}
