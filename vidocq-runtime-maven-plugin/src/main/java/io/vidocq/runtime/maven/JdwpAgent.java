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

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;

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

    public JdwpAgent {
        host = host == null || host.isBlank() ? DEFAULT_HOST : host.strip();
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

    /** The host as an IP address, without a name lookup, or {@code null} when it is a name or {@code *}. */
    private InetAddress literal() {
        String address = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(address);
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
    }
}
