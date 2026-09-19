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
package io.vidocq.runtime.cli.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugOptionsTest {

    @Test
    void defaultsUseStandardPortAndNoSuspend() {
        DebugOptions opts = DebugOptions.defaults();
        assertEquals(5005, opts.port());
        assertFalse(opts.suspend());
    }

    @Test
    void agentArgumentReflectsSuspendFlag() {
        assertTrue(new DebugOptions(5005, false).agentArgument().contains("suspend=n"));
        assertTrue(new DebugOptions(5005, true).agentArgument().contains("suspend=y"));
    }

    @Test
    void agentArgumentIsAWellFormedJdwpString() {
        String arg = new DebugOptions(6006, false).agentArgument();
        assertEquals("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:6006", arg);
    }

    /** Whoever reaches a JDWP agent can run any code in the JVM: the hint never opens it to the network. */
    @Test
    void theAgentListensOnTheLoopbackInterfaceOnly() {
        assertTrue(DebugOptions.defaults().agentArgument().endsWith(",address=127.0.0.1:5005"));
        assertFalse(DebugOptions.defaults().agentArgument().contains("*"));
    }

    @Test
    void hintMentionsTheAddress() {
        assertTrue(new DebugOptions(5005, false).hint().contains("127.0.0.1:5005"));
    }

    @Test
    void suspendHintMentionsWaiting() {
        assertTrue(new DebugOptions(5005, true).hint().toLowerCase().contains("wait"));
    }

    @Test
    void invalidPortIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DebugOptions(0, false));
        assertThrows(IllegalArgumentException.class, () -> new DebugOptions(70000, false));
    }
}
