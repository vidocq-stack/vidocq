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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a failed boot says it was doing: the current step, else the current phase, else the boot itself. */
class StartupRecorderTest {

    @Test
    void aFailureOutsideAnyPhaseIsTheBoot() {
        assertEquals("boot", new StartupRecorder().failing());
    }

    @Test
    void aFailureIsNamedByTheStepThenByThePhase() {
        StartupRecorder recorder = new StartupRecorder();

        recorder.begin("configuration");
        assertEquals("configuration", recorder.failing());

        recorder.step("onStart chappe-bootstrap");
        assertEquals("onStart chappe-bootstrap", recorder.failing());

        recorder.step(null);
        assertEquals("configuration", recorder.failing());

        recorder.end();
        assertEquals("boot", recorder.failing());
    }
}
