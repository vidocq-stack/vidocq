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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The JDWP agent {@code vidocq:dev} and {@code vidocq:run} add to the JVM they fork.
 *
 * <p>A JDWP agent gives whoever connects to it the power to run any code in the JVM, so it listens on the
 * loopback interface unless told otherwise: {@link #DEFAULT_HOST}. The host is passed to the agent as it is
 * written, so every spelling the JVM understands works: {@code *} or {@code 0.0.0.0} (or {@code ::}) opens it on
 * every interface, and an address or a host name opens it on that interface only. Anything that is not
 * {@code localhost} or a loopback address ({@code 127.0.0.1}, {@code 127.x.y.z}, {@code ::1}) is reachable from
 * the network, which {@link #exposure(String)} says.
 *
 * @param host    the interface the agent listens on; blank means {@link #DEFAULT_HOST}
 * @param port    the port the agent listens on
 * @param suspend whether the JVM waits for a debugger before running ({@code suspend=y})
 */
public record JdwpAgent(String host, int port, boolean suspend) {

    /** The interface the agent listens on unless told otherwise: this machine only. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** The port the agent listens on unless told otherwise. */
    public static final int DEFAULT_PORT = 5005;

    private static final int MAX_PORT = 65_535;

    public JdwpAgent {
        host = host == null || host.isBlank() ? DEFAULT_HOST : host.strip();
    }

    /**
     * The agent of a goal's parameters.
     *
     * @param port        the port the user set, or {@code null} when none was: then {@code defaultPort}
     * @param defaultPort {@link #DEFAULT_PORT}, another port in the unit tests only
     */
    public static JdwpAgent of(String host, Integer port, int defaultPort, boolean suspend) {
        return new JdwpAgent(host, port == null ? defaultPort : port, suspend);
    }

    /**
     * This agent, its port checked before the JVM is forked: a JVM whose agent cannot listen dies at start with
     * {@code transport error 202: bind failed}, and nothing else says the debug port was the cause
     * (BUG-20261114-03).
     *
     * <p>A busy port the user did not set moves to a free one on the same host, with a warning: a second
     * {@code vidocq:dev} on the machine then starts. A busy port the user set fails: the debugger attaches to that
     * port, so another one would be a silent switch. The check binds the address as the agent will, so it agrees
     * with the JVM about what is busy, and something may still take the port before the JVM binds it: the check is
     * there for a clear message, not a guarantee. Port {@code 0}, which lets the JVM choose a port, an invalid port
     * and a host this machine cannot listen on are left to the JVM, whose own error says what is wrong.
     *
     * @param portSet      whether the user set the port, rather than leaving the default one
     * @param portProperty the property that sets the port, named by the warning and by the failure
     * @param warn         receives the warning when the agent moves to another port
     * @return this agent, or an agent on a free port of the same host
     * @throws MojoExecutionException when the port the user set is busy
     */
    public JdwpAgent withFreePort(boolean portSet, String portProperty, Consumer<String> warn)
            throws MojoExecutionException {
        if (port <= 0 || port > MAX_PORT || bind(port) == port) {
            return this;
        }
        int free = bind(0);
        if (free <= 0) {
            return this;
        }
        if (portSet) {
            throw new MojoExecutionException("The debug port " + port + " is already in use on " + host + ", and "
                    + portProperty + " sets it: the debug agent does not move to another port, since the debugger"
                    + " attaches to this one. Stop what listens on " + port + ", or set " + portProperty
                    + " to a free port.");
        }
        JdwpAgent moved = new JdwpAgent(host, free, suspend);
        warn.accept("The debug port " + port + " is already in use on " + host + ": the debug agent listens on "
                + moved.address() + " instead, attach the debugger there. Choose its port with -D" + portProperty
                + "=<port>.");
        return moved;
    }

    /** {@code -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005}. */
    public String argument() {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n")
                + ",address=" + address();
    }

    /** {@code 127.0.0.1:5005}: what the agent is told, and what a debugger attaches to. */
    public String address() {
        return host + ":" + port;
    }

    /** Whether only this machine can reach the agent: {@code localhost} or a loopback address. */
    public boolean loopback() {
        if (host.toLowerCase(Locale.ROOT).equals("localhost")) {
            return true;
        }
        InetAddress literal = literal();
        return literal != null && literal.isLoopbackAddress();
    }

    /** Whether the agent listens on every interface: {@code *}, {@code 0.0.0.0} or {@code ::}. */
    public boolean everyInterface() {
        if (host.equals("*")) {
            return true;
        }
        InetAddress literal = literal();
        return literal != null && literal.isAnyLocalAddress();
    }

    /**
     * {@code port 5005, host 127.0.0.1}, or {@code port 5005, host * (every interface)}: the end of the line
     * that announces the agent, after {@code Debug agent (JDWP) on }.
     */
    public String where() {
        return "port " + port + ", host " + host + (everyInterface() ? " (every interface)" : "");
    }

    /**
     * The warning to log when the agent can be reached from other machines, or empty for a loopback host.
     *
     * @param property the property that chose the host, so that the warning says what to change
     */
    public Optional<String> exposure(String property) {
        if (loopback()) {
            return Optional.empty();
        }
        return Optional.of(property + "=" + host + " makes the debugger reachable from the network: the JDWP agent"
                + " listens on " + (everyInterface() ? "every interface" : "that interface") + ", port " + port
                + ", and whoever connects to it can run any code in the application JVM. Leave " + property
                + " at its default, " + DEFAULT_HOST + ", unless every machine that can reach this port is trusted.");
    }

    /**
     * Binds a server socket to the agent's host and {@code candidate}, as the agent will, and closes it.
     *
     * @param candidate a port, or {@code 0} for any free one
     * @return the port the socket got, or {@code -1} when it could not be bound: the port is busy, or the host is
     *         not an address of this machine or cannot be resolved
     */
    private int bind(int candidate) {
        InetSocketAddress address;
        if (host.equals("*")) {
            address = new InetSocketAddress(candidate);
        } else {
            InetAddress literal = literal();
            // A host name is resolved here, as the agent resolves it; one that cannot be fails the bind below.
            address = literal != null
                    ? new InetSocketAddress(literal, candidate)
                    : new InetSocketAddress(host, candidate);
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(address);
            return socket.getLocalPort();
        } catch (IOException _) {
            return -1;
        }
    }

    /** The host as an IP address, without a name lookup, or {@code null} when it is a name or {@code *}. */
    private InetAddress literal() {
        String address = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(address);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
