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
package io.vidocq.runtime.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigKeysTest {

    @Test
    void knownNamespacesAreRecognized() {
        assertTrue(ConfigKeys.isKnown("vidocq.http.port"));
        assertTrue(ConfigKeys.isKnown("vidocq.dev.debug"));
        assertTrue(ConfigKeys.isKnown("vidocq.pool.main.url"));
    }

    @Test
    void coreConsoleKeysAreRecognized() {
        // read by vidocq-runtime-core (console logging), so `vidocq doctor` must not flag them
        assertTrue(ConfigKeys.isKnown("vidocq.log.console"));
        assertTrue(ConfigKeys.isKnown("vidocq.console.color"));
    }

    @Test
    void coreBannerKeysAreRecognized() {
        // read by vidocq-runtime-core (startup banner)
        assertTrue(ConfigKeys.isKnown("vidocq.banner.mode"));
        assertTrue(ConfigKeys.isKnown("vidocq.banner.location"));
    }

    @Test
    void theLaunchModeKeyIsRecognized() {
        // read by vidocq-runtime-core (launch mode), so `vidocq doctor` must not flag it
        assertTrue(ConfigKeys.isKnown("vidocq.launch.mode"));
    }

    @Test
    void theStartupReportKeyIsRecognized() {
        // read by vidocq-runtime-core (startup report), so `vidocq doctor` must not flag it
        assertTrue(ConfigKeys.isKnown("vidocq.startup.report"));
        assertFalse(ConfigKeys.isKnown("vidocq.startpu.report"), "a typo in the namespace is still reported");
    }

    @Test
    void unknownVidocqNamespacesAreRejected() {
        assertFalse(ConfigKeys.isKnown("vidocq.htpp.port"));
        assertFalse(ConfigKeys.isKnown("vidocq.bogus"));
        assertFalse(ConfigKeys.isKnown("vidocq.lanch.mode"), "a typo in the namespace is still reported");
    }

    @Test
    void nonVidocqKeysAreAlwaysAllowed() {
        assertTrue(ConfigKeys.isKnown("my.app.setting"));
        assertTrue(ConfigKeys.isKnown("logging.level"));
        assertTrue(ConfigKeys.isKnown("server"));
    }

    @Test
    void unknownKeysAreFilteredSortedAndDeduplicated() {
        List<String> unknown = ConfigKeys.unknownKeys(List.of(
                "vidocq.http.port",
                "vidocq.zzz",
                "vidocq.htpp.port",
                "vidocq.htpp.port",
                "my.app.setting"));

        assertEquals(List.of("vidocq.htpp.port", "vidocq.zzz"), unknown);
    }

    @Test
    void allKnownKeysYieldNoUnknowns() {
        assertTrue(ConfigKeys.unknownKeys(List.of(
                "vidocq.http.port", "vidocq.dev.debug", "app.name")).isEmpty());
    }

    /** Vidocq/vidocq#353: {@code vidocq config set} names the key the user most likely meant. */
    @Test
    void anUnknownNamespaceSuggestsTheClosestKnownOne() {
        assertEquals(Optional.of("vidocq.http.port"), ConfigKeys.suggestion("vidocq.htpp.port"));
        assertEquals(Optional.of("vidocq.launch.mode"), ConfigKeys.suggestion("vidocq.lanch.mode"));
        assertEquals(Optional.of("vidocq.profile"), ConfigKeys.suggestion("vidocq.profil"));
    }

    @Test
    void aKnownKeyOrAFarOneHasNoSuggestion() {
        assertEquals(Optional.empty(), ConfigKeys.suggestion("vidocq.http.port"));
        assertEquals(Optional.empty(), ConfigKeys.suggestion("vidocq.zzzzzzzz"));
        assertEquals(Optional.empty(), ConfigKeys.suggestion("my.app.setting"));
        assertEquals(Optional.empty(), ConfigKeys.suggestion("vidocq."));
    }
}
