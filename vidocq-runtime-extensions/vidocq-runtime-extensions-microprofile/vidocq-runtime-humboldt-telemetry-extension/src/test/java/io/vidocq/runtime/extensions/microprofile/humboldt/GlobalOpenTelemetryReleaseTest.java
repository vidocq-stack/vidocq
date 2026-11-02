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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GlobalOpenTelemetry} can be set once per JVM. A {@code vidocq dev} hot reload boots the runtime again in the
 * same JVM: the stop of the first boot must free it, or every reload warns "was already set" and replaces it.
 */
class GlobalOpenTelemetryReleaseTest {

    @BeforeEach
    @AfterEach
    void free() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void installingTakesTheGlobalSlot() {
        assertTrue(HumboldtExtension.installGlobal(OpenTelemetry.noop(), "app"));
        OpenTelemetry other = OpenTelemetry.noop();
        assertThrows(IllegalStateException.class, () -> GlobalOpenTelemetry.set(other));
    }

    @Test
    void releasingFreesTheSlotForTheNextBoot() {
        HumboldtExtension.installGlobal(OpenTelemetry.noop(), "app");

        HumboldtExtension.releaseGlobal();

        OpenTelemetry next = OpenTelemetry.noop();
        assertDoesNotThrow(() -> GlobalOpenTelemetry.set(next));
    }

    @Test
    void aBootThatFindsTheSlotTakenReplacesItsOccupant() {
        GlobalOpenTelemetry.set(OpenTelemetry.noop());

        assertTrue(HumboldtExtension.installGlobal(OpenTelemetry.noop(), "app"));
    }

    @Test
    void theStopOfABootThatTookTheSlotFreesIt() {
        HumboldtExtension extension = new HumboldtExtension();
        extension.installedGlobally(HumboldtExtension.installGlobal(OpenTelemetry.noop(), "app"));

        extension.releaseIfInstalled();

        OpenTelemetry next = OpenTelemetry.noop();
        assertDoesNotThrow(() -> GlobalOpenTelemetry.set(next));
    }

    @Test
    void theStopOfABootThatDidNotTakeTheSlotLeavesItAlone() {
        GlobalOpenTelemetry.set(OpenTelemetry.noop());
        HumboldtExtension extension = new HumboldtExtension();

        extension.releaseIfInstalled();

        OpenTelemetry other = OpenTelemetry.noop();
        assertThrows(IllegalStateException.class, () -> GlobalOpenTelemetry.set(other));
    }
}
