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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JDWP agent {@code vidocq:dev} and {@code vidocq:run} fork their JVM with. */
class JdwpAgentTest {

    @Test
    void theDefaultHostIsTheLoopbackInterface() {
        JdwpAgent agent = new JdwpAgent(null, 5005, false);

        assertEquals("127.0.0.1", agent.host());
        assertEquals("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005",
                agent.argument());
        assertEquals("port 5005, host 127.0.0.1", agent.where());
        assertTrue(agent.loopback());
        assertEquals(Optional.empty(), agent.exposure("vidocq.dev.debugHost"));
    }

    @Test
    void aBlankHostIsTheDefaultOneNeverEveryInterface() {
        assertEquals("127.0.0.1:5005", new JdwpAgent("", 5005, false).address());
        assertEquals("127.0.0.1:5005", new JdwpAgent("  ", 5005, false).address());
    }

    @Test
    void suspendIsPassedOn() {
        assertEquals("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:18095",
                new JdwpAgent("127.0.0.1", 18095, true).argument());
    }

    /** The host reaches the agent as it is written: the JVM understands every one of these spellings. */
    @Test
    void anExplicitHostIsPassedAsWritten() {
        assertEquals("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                new JdwpAgent("*", 5005, false).argument());
        assertEquals("0.0.0.0:5005", new JdwpAgent(" 0.0.0.0 ", 5005, false).address());
        assertEquals("[::1]:5005", new JdwpAgent("[::1]", 5005, false).address());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.1.1", "localhost", "LocalHost", "::1", "[::1]"})
    void loopbackHostsNeedNoWarning(String host) {
        JdwpAgent agent = new JdwpAgent(host, 5005, false);

        assertTrue(agent.loopback(), host);
        assertFalse(agent.everyInterface(), host);
        assertEquals(Optional.empty(), agent.exposure("vidocq.dev.debugHost"), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "0.0.0.0", "::", "[::]"})
    void everyInterfaceIsSaidSo(String host) {
        JdwpAgent agent = new JdwpAgent(host, 5005, false);

        assertTrue(agent.everyInterface(), host);
        assertFalse(agent.loopback(), host);
        assertEquals("port 5005, host " + host + " (every interface)", agent.where());
    }

    @Test
    void everyInterfaceIsAWarningThatNamesTheRisk() {
        String warning = new JdwpAgent("*", 5005, false).exposure("vidocq.dev.debugHost").orElseThrow();

        assertEquals("vidocq.dev.debugHost=* makes the debugger reachable from the network: the JDWP agent listens"
                + " on every interface, port 5005, and whoever connects to it can run any code in the application"
                + " JVM. Leave vidocq.dev.debugHost at its default, 127.0.0.1, unless every machine that can reach"
                + " this port is trusted.", warning);
    }

    /** A LAN address or a host name is one interface, but still one other machines can reach. */
    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.20", "10.0.0.5", "fe80::1", "build-box.example"})
    void anyOtherHostIsReachableFromTheNetwork(String host) {
        JdwpAgent agent = new JdwpAgent(host, 18099, false);

        assertFalse(agent.loopback(), host);
        assertFalse(agent.everyInterface(), host);
        assertEquals("port 18099, host " + host, agent.where());
        String warning = agent.exposure("vidocq.run.debug.host").orElseThrow();
        assertTrue(warning.startsWith("vidocq.run.debug.host=" + host
                + " makes the debugger reachable from the network: the JDWP agent listens on that interface,"
                + " port 18099"), warning);
    }
}
