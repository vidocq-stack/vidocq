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
package io.vidocq.runtime.extensions.jakartaee.core.cassini.dev;

import io.vidocq.runtime.extensions.jakartaee.core.cassini.live.RestMount;
import io.vidocq.runtime.extensions.jakartaee.core.cassini.live.RestMounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rest section, live: what {@link RestLivePanel} reads from the mounts {@code RestMounts} holds. */
class RestLivePanelTest {

    @AfterEach
    void forget() {
        RestMounts.clear();
    }

    @Test
    void itMakesTheRestSectionLiveWithItsCharts() {
        RestLivePanel panel = new RestLivePanel();

        assertEquals("rest", panel.id());
        assertEquals(RestPanel.CHARTS, panel.charts());
    }

    @Test
    void samplesTheMountsOfThisBoot() {
        var stats = new RestPanelTest.FixedStatistics(3, 0, new long[] {0, 3, 0, 0, 0}, 3, 1);
        RestMounts.record(RestMount.of("vidocq.rest", "default", "", true, Set.of(), List.of(), stats));
        RecordingSample sample = new RecordingSample();

        new RestLivePanel().sample(sample);

        assertEquals(3, sample.groups().get("vidocq.rest").number("requests"));
    }

    @Test
    void nothingMountedIsAbsentNotZero() {
        RecordingSample sample = new RecordingSample();

        new RestLivePanel().sample(sample);

        assertEquals("absent", sample.kind("requests"));
        assertEquals("no REST resource mounted", sample.text("requests"));
    }
}
