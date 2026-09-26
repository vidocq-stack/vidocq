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
package io.vidocq.runtime.extensions.microprofile.knock.live;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The name a check is shown under, and its key in the {@code health} panel. */
class CheckKeysTest {

    @Test
    void theDisplayNameIsTheSimpleClassNameWithoutTheContainerSuffix() {
        assertEquals("AppLivenessCheck", CheckKeys.display("com.acme.AppLivenessCheck_ClientProxy"));
        assertEquals("DbCheck", CheckKeys.display("com.acme.DbCheck$$Intercepted"));
        assertEquals("Outer.Inner", CheckKeys.display("com.acme.Outer$Inner"));
        assertEquals("plain", CheckKeys.display("plain"));
    }

    @Test
    void aKeyIsTheDisplayNameInLowercaseWithItsWordsHyphenated() {
        assertEquals("app-liveness-check", CheckKeys.normalize("AppLivenessCheck"));
        assertEquals("outer.inner", CheckKeys.normalize("Outer.Inner"));
        assertEquals("c-2fa-check", CheckKeys.normalize("2faCheck"));
        assertEquals("db-http-check", CheckKeys.normalize("DbHTTPCheck"));
    }

    @Test
    void aKeyLeavesRoomForItsSuffixAndIsNumberedOnAClash() {
        CheckKeys keys = new CheckKeys(Set.of("status", "up", "down", "checks"));
        String longName = "AVeryLongHealthCheckNameThatGoesOnAndOnAndOn";

        String first = keys.allocate(longName);
        String second = keys.allocate(longName);
        String reserved = keys.allocate("Status");

        assertEquals(40 - CheckKeys.AT.length(), first.length());
        PanelSample.requireKey(first + CheckKeys.AT);
        assertEquals(first.substring(0, first.length() - 2) + "-2", second);
        assertEquals("status-2", reserved);
    }
}
