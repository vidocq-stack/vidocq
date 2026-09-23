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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CassiniExtensionTest {

    @AfterEach
    void forget() {
        RestMounts.clear();
    }

    @Test
    void contributes_the_rest_section() {
        CassiniExtension extension = new CassiniExtension();

        assertEquals("rest", extension.id());
        assertEquals("REST (Cassini)", extension.title());
    }

    @Test
    void writes_what_the_mounts_of_this_boot_recorded() {
        RestMounts.record(RestMount.of("vidocq.rest", "default", "", true, Set.of(), List.of()));
        RestMounts.record(RestMount.of("health", "default", "/health", false, Set.of(), List.of()));
        RecordingSection section = new RecordingSection();

        new CassiniExtension().contribute(new FakeReportContext(Verbosity.SUMMARY), section);

        assertEquals("0 resource classes, 0 routes, 0 providers at /, /health", section.summary);
    }

    @Test
    void forgets_the_mounts_when_it_stops() {
        RestMounts.record(RestMount.of("vidocq.rest", "default", "", true, Set.of(), List.of()));

        new CassiniExtension().onStop();

        assertTrue(RestMounts.all().isEmpty());
    }
}
