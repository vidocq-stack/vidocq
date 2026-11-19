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
package io.vidocq.runtime.extensions.jakartaee.web.foy.dev;

import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMount;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The servlet section, live: the active sessions of each servlet deployment of the boot. */
class ServletLivePanelTest {

    private final Object boot = new Object();

    @AfterEach
    void forget() {
        ServletMounts.forget(boot);
    }

    @Test
    void itMakesTheServletSectionLiveWithItsChart() {
        ServletLivePanel panel = new ServletLivePanel();

        assertEquals("servlet", panel.id());
        assertEquals(ServletLivePanel.CHARTS, panel.charts());
    }

    @Test
    void samplesTheActiveSessionsOfThisBoot() {
        AtomicInteger sessions = new AtomicInteger(3);
        ServletMounts.register(boot, new ServletMount("vidocq.servlet", "default", "/shop", sessions::get));
        ServletLivePanel panel = new ServletLivePanel(boot);
        RecordingSample sample = new RecordingSample();

        panel.sample(sample);
        sessions.set(5);
        RecordingSample later = new RecordingSample();
        panel.sample(later);

        assertEquals(3, sample.groups().get("vidocq.servlet").number("sessions"));
        assertEquals(5, later.groups().get("vidocq.servlet").number("sessions"));
    }

    @Test
    void anotherBootsMountsAreNotSampled() {
        ServletMounts.register(new Object(), new ServletMount("vidocq.servlet", "default", "/", () -> 9));
        RecordingSample sample = new RecordingSample();

        new ServletLivePanel(boot).sample(sample);

        assertEquals("absent", sample.kind("sessions"));
    }

    @Test
    void nothingMountedIsAbsentNotZero() {
        RecordingSample sample = new RecordingSample();

        new ServletLivePanel(boot).sample(sample);

        assertEquals("absent", sample.kind("sessions"));
        assertEquals("no servlet deployed", sample.text("sessions"));
    }
}
