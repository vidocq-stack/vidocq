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
package io.vidocq.runtime.core.banner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.vidocq.runtime.core.banner.BannerTestSupport.IDEA_AGENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JDWP agent read from the JVM input arguments. */
class DebugAgentTest {

    @Test
    void anAgentlibListeningOnEveryInterface() {
        Optional<DebugAgent> agent = DebugAgent.detect(List.of("-Xmx512m", IDEA_AGENT,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"));

        assertTrue(agent.isPresent());
        assertEquals("*:5005", agent.get().address());
        assertFalse(agent.get().suspend());
        assertEquals("debug *:5005", agent.get().segment());
    }

    @Test
    void aSuspendedJvmSaysSo() {
        DebugAgent agent = DebugAgent.detect(List.of(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005")).orElseThrow();

        assertEquals("127.0.0.1:5005", agent.address());
        assertTrue(agent.suspend());
        assertEquals("debug 127.0.0.1:5005 suspend=y", agent.segment());
    }

    @Test
    void theOlderXrunjdwpFormIsReadToo() {
        DebugAgent agent = DebugAgent.detect(List.of(
                "-Xrunjdwp:transport=dt_socket,address=localhost:18095,server=y,suspend=y")).orElseThrow();

        assertEquals("debug localhost:18095 suspend=y", agent.segment());
    }

    @Test
    void anAgentWithoutAnAddressIsStillADebugger() {
        DebugAgent agent = DebugAgent.detect(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y"))
                .orElseThrow();

        assertEquals(null, agent.address(), "the JVM picks the port and only its own message tells which");
        assertEquals("debug suspend=y", agent.segment());
    }

    @Test
    void noAgentNoSegment() {
        assertEquals(Optional.empty(), DebugAgent.detect(List.of("-Xmx512m", IDEA_AGENT, "-Dvidocq.profile=dev")));
        assertEquals(Optional.empty(), DebugAgent.detect(List.of()));
        assertEquals(Optional.empty(), DebugAgent.detect(null));
        assertEquals(Optional.empty(), DebugAgent.detect(java.util.Arrays.asList((String) null)));
    }

    @Test
    void anUnreadableOptionIsSkippedRatherThanGuessed() {
        DebugAgent agent = DebugAgent.detect(List.of("-agentlib:jdwp=server,address=,suspend=maybe")).orElseThrow();

        assertEquals(null, agent.address());
        assertFalse(agent.suspend(), "only y, yes or true means a suspended JVM");
        assertEquals("debug", agent.segment());
    }
}
