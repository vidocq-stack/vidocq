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

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * A second {@code vidocq:dev} on the machine: the default port is held by the first one. The JVM died at start
     * with {@code transport error 202: bind failed}; the agent now moves to a free port and says so (BUG-20261114-03).
     */
    @Test
    void aBusyDefaultPortMovesTheAgentToAFreeOneWithAWarning() throws Exception {
        List<String> warnings = new ArrayList<>();
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = holder.getLocalPort();

            JdwpAgent agent = new JdwpAgent("127.0.0.1", busy, true)
                    .withFreePort(false, "vidocq.dev.debugPort", warnings::add);

            assertNotEquals(busy, agent.port());
            assertTrue(agent.port() > 0, agent.toString());
            assertEquals("127.0.0.1", agent.host());
            assertTrue(agent.suspend());
            assertBindable(agent.port());
            assertEquals(List.of("The debug port " + busy + " is already in use on 127.0.0.1: the debug agent listens"
                    + " on 127.0.0.1:" + agent.port() + " instead, attach the debugger there. Choose its port with"
                    + " -Dvidocq.dev.debugPort=<port>."), warnings);
        }
    }

    /** A port the user set is the one the IDE attaches to: never replaced, the goal fails at once. */
    @Test
    void aBusyPortSetByTheUserFailsNamingThePortAndTheProperty() throws Exception {
        List<String> warnings = new ArrayList<>();
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = holder.getLocalPort();
            JdwpAgent agent = new JdwpAgent(null, busy, false);

            MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                    () -> agent.withFreePort(true, "vidocq.run.debug.port", warnings::add));

            assertEquals("The debug port " + busy + " is already in use on 127.0.0.1, and vidocq.run.debug.port sets"
                    + " it: the debug agent does not move to another port, since the debugger attaches to this one."
                    + " Stop what listens on " + busy + ", or set vidocq.run.debug.port to a free port.",
                    failure.getMessage());
            assertEquals(List.of(), warnings);
        }
    }

    @Test
    void everyInterfaceIsBusyWhenAnotherProcessListensOnIt() throws Exception {
        List<String> warnings = new ArrayList<>();
        try (ServerSocket holder = new ServerSocket(0)) {
            JdwpAgent agent = new JdwpAgent("*", holder.getLocalPort(), false);

            assertThrows(MojoExecutionException.class,
                    () -> agent.withFreePort(true, "vidocq.dev.debugPort", warnings::add));
            JdwpAgent moved = agent.withFreePort(false, "vidocq.dev.debugPort", warnings::add);

            assertNotEquals(agent.port(), moved.port());
            assertEquals("*", moved.host());
            assertEquals(1, warnings.size(), warnings.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFreePortIsKeptSilently(boolean portSet) throws Exception {
        List<String> warnings = new ArrayList<>();
        int free;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            free = probe.getLocalPort();
        }
        JdwpAgent agent = new JdwpAgent("localhost", free, false);

        assertSame(agent, agent.withFreePort(portSet, "vidocq.dev.debugPort", warnings::add));
        assertEquals(List.of(), warnings);
    }

    /** {@code 0} lets the JVM choose a free port itself, and print it: nothing to check. */
    @Test
    void portZeroIsLeftToTheJvm() throws Exception {
        List<String> warnings = new ArrayList<>();
        JdwpAgent agent = new JdwpAgent(null, 0, false);

        assertSame(agent, agent.withFreePort(true, "vidocq.dev.debugPort", warnings::add));
        assertEquals(List.of(), warnings);
    }

    /**
     * A host this machine cannot listen on is not a busy port: the JVM's own error says what is wrong with it.
     * {@code 192.0.2.1} is TEST-NET-1 (RFC 5737), never the address of a local interface; {@code -1} and
     * {@code 65536} are no port.
     */
    @Test
    void whatCannotBeCheckedIsLeftToTheJvm() throws Exception {
        List<String> warnings = new ArrayList<>();
        for (JdwpAgent agent : List.of(new JdwpAgent("192.0.2.1", 18099, false), new JdwpAgent(null, -1, false),
                new JdwpAgent(null, 65536, false))) {
            assertSame(agent, agent.withFreePort(true, "vidocq.dev.debugPort", warnings::add), agent.toString());
        }
        assertEquals(List.of(), warnings);
    }

    private static void assertBindable(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(port, socket.getLocalPort());
        }
    }
}
