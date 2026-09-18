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
package io.vidocq.runtime.extensions.essentials.devconsole;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Keeps other sites out of the console through DNS rebinding: a page of {@code evil.example.com} whose name is made
 * to resolve to {@code 127.0.0.1} reaches the console's port as a same-origin request, but its {@code Host} header
 * still says {@code evil.example.com}. The console answers only a request whose {@code Host} is one of its own
 * addresses, optionally followed by the port it bound:
 * <ul>
 *   <li>{@code localhost}, {@code 127.0.0.1} or {@code [::1]};</li>
 *   <li>the configured host, whatever it is;</li>
 *   <li>when the configured host is not a loopback one, the console being open to the network, any IP address: a
 *       browser writes the address it was given, and an address, unlike a name, cannot be rebound.</li>
 * </ul>
 * Anything else, a missing {@code Host} included, gets {@code 403}.
 */
final class HostGuard {

    private final String configuredHost;
    private final boolean openToTheNetwork;

    /** @param configuredHost the host the console listens on, as configured: a name or an address, no brackets */
    HostGuard(String configuredHost) {
        this.configuredHost = configuredHost.toLowerCase(Locale.ROOT);
        this.openToTheNetwork = !loopback(this.configuredHost);
    }

    /**
     * Whether a request carrying {@code host} may be answered.
     *
     * @param host      the {@code Host} header, such as {@code localhost:8888}, or {@code null}
     * @param boundPort the port the console listens on, {@code 0} while it is not known yet
     */
    boolean allows(String host, int boundPort) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String name;
        String port;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) {
                return false;
            }
            name = host.substring(1, close);
            String rest = host.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                return false;
            }
            port = rest.isEmpty() ? null : rest.substring(1);
            if (!ipAddress(name)) {
                return false;
            }
        } else {
            int colon = host.lastIndexOf(':');
            name = colon < 0 ? host : host.substring(0, colon);
            port = colon < 0 ? null : host.substring(colon + 1);
            if (name.indexOf(':') >= 0) {
                return false; // an IPv6 address without its brackets
            }
        }
        return (port == null || samePort(port, boundPort)) && allowedName(name.toLowerCase(Locale.ROOT));
    }

    private boolean allowedName(String name) {
        return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("::1")
                || name.equals(configuredHost) || (openToTheNetwork && ipAddress(name));
    }

    /** Whether {@code port} is the bound port, written as ASCII digits. */
    private static boolean samePort(String port, int boundPort) {
        if (boundPort <= 0 || port.isEmpty() || port.length() > 5
                || !port.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        return Integer.parseInt(port) == boundPort;
    }

    /** Whether {@code name} is an IPv4 or IPv6 address literal, read from the text alone: no name is resolved. */
    private static boolean ipAddress(String name) {
        if (name.isEmpty() || !name.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':'
                || c == '%')) {
            return false;
        }
        try {
            InetAddress.ofLiteral(name);
            return true;
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }

    /** Whether {@code host} names this machine only: {@code localhost}, or a loopback address literal. */
    private static boolean loopback(String host) {
        if (host.equals("localhost")) {
            return true;
        }
        return ipAddress(host) && InetAddress.ofLiteral(host).isLoopbackAddress();
    }
}
