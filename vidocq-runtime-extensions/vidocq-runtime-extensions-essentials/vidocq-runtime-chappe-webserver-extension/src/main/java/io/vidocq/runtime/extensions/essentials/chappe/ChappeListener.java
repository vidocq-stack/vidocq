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
package io.vidocq.runtime.extensions.essentials.chappe;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Objects;

/**
 * Description of a Chappe listener (a host:port pair, with or without TLS).
 * <p>
 * Chappe allocates one {@link io.vidocq.chappe.api.Server} instance per listener.
 * Contributing extensions identify the listener by its {@link #name()}.
 * </p>
 *
 * @param name logical name (e.g. {@code default}, {@code admin})
 * @param host listening host
 * @param port listening port
 * @param tls {@code true} to enable TLS (reserved — not implemented in this milestone)
 */
public record ChappeListener(String name, String host, int port, boolean tls) {

    /** Default listener name. */
    public static final String DEFAULT = "default";

    public ChappeListener {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        if (name.isBlank()) {
            throw new IllegalArgumentException("listener name must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
    }

    /** Simple HTTP listener. */
    public static ChappeListener http(String name, String host, int port) {
        return new ChappeListener(name, host, port, false);
    }

    /**
     * The URL a browser opens for a listener bound at {@code bound}, as {@link ChappeServerBootstrap} logs it:
     * {@code http://127.0.0.1:8888/}. A wildcard address ({@code 0.0.0.0}, {@code ::}) reads {@code localhost};
     * an IPv6 literal is bracketed, in its short form ({@code [::1]}), its zone, if any, escaped as {@code %25}.
     * The URL ends with its {@code /}, nothing after it, so that a terminal can link it.
     *
     * @param bound the address a server listens on, such as the one
     *        {@link ListenerOptions#onBound() onBound} receives
     * @return the URL, never {@code null}
     */
    public static String httpUrl(InetSocketAddress bound) {
        Objects.requireNonNull(bound, "bound");
        InetAddress address = bound.getAddress();
        String host;
        if (address == null) {
            String name = bound.getHostString();
            host = name.indexOf(':') >= 0 ? "[" + name + "]" : name;
        } else if (address.isAnyLocalAddress()) {
            host = "localhost";
        } else if (address instanceof Inet6Address) {
            host = "[" + shortIpv6(address.getHostAddress()) + "]";
        } else {
            host = address.getHostAddress();
        }
        return "http://" + host + ":" + bound.getPort() + "/";
    }

    /**
     * {@code 0:0:0:0:0:0:0:1%lo0} as {@code ::1%25lo0}: the longest run of two zero groups or more, the first of
     * equal runs, becomes {@code ::} (RFC 5952), and the zone separator is percent-encoded (RFC 6874).
     * {@link InetAddress#getHostAddress()} already writes lowercase groups without leading zeros.
     */
    private static String shortIpv6(String full) {
        int zone = full.indexOf('%');
        String suffix = zone < 0 ? "" : "%25" + full.substring(zone + 1);
        String[] groups = (zone < 0 ? full : full.substring(0, zone)).split(":", -1);
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < groups.length; ) {
            if (!"0".equals(groups[i])) {
                i++;
                continue;
            }
            int start = i;
            while (i < groups.length && "0".equals(groups[i])) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        if (bestStart < 0) {
            return String.join(":", groups) + suffix;
        }
        String head = String.join(":", Arrays.copyOfRange(groups, 0, bestStart));
        String tail = String.join(":", Arrays.copyOfRange(groups, bestStart + bestLength, groups.length));
        return head + "::" + tail + suffix;
    }
}
