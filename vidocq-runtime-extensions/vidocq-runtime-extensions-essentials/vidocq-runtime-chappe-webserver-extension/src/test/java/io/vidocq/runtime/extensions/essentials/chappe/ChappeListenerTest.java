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

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class ChappeListenerTest {

    @Test
    void httpFactoryBuildsNonTlsListener() {
        ChappeListener l = ChappeListener.http("admin", "127.0.0.1", 9090);
        assertEquals("admin", l.name());
        assertEquals("127.0.0.1", l.host());
        assertEquals(9090, l.port());
        assertFalse(l.tls());
    }

    @Test
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChappeListener(" ", "0.0.0.0", 8080, false));
    }

    @Test
    void nullNameRejected() {
        assertThrows(NullPointerException.class,
                () -> new ChappeListener(null, "0.0.0.0", 8080, false));
    }

    @Test
    void nullHostRejected() {
        assertThrows(NullPointerException.class,
                () -> new ChappeListener("default", null, 8080, false));
    }

    @Test
    void negativePortRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChappeListener("default", "0.0.0.0", -1, false));
    }

    @Test
    void portAboveMaxRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChappeListener("default", "0.0.0.0", 70_000, false));
    }

    @Test
    void defaultConstantExposedAsDefault() {
        assertEquals("default", ChappeListener.DEFAULT);
    }

    // --- httpUrl: the address a listener bound, as a browser opens it ------------------------------

    @Test
    void ipv4LiteralStaysLiteral() throws Exception {
        assertEquals("http://127.0.0.1:8888/",
                ChappeListener.httpUrl(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8888)));
    }

    @Test
    void ipv4WildcardReadsLocalhost() {
        assertEquals("http://localhost:8080/", ChappeListener.httpUrl(new InetSocketAddress(8080)));
    }

    @Test
    void ipv6WildcardReadsLocalhost() throws Exception {
        assertEquals("http://localhost:8080/",
                ChappeListener.httpUrl(new InetSocketAddress(InetAddress.getByName("::"), 8080)));
    }

    @Test
    void ipv6LiteralIsBracketedAndCompressed() throws Exception {
        assertEquals("http://[::1]:8888/",
                ChappeListener.httpUrl(new InetSocketAddress(InetAddress.getByName("::1"), 8888)));
        assertEquals("http://[2001:db8::1:0:0:1]:80/",
                ChappeListener.httpUrl(new InetSocketAddress(InetAddress.getByName("2001:db8:0:0:1:0:0:1"), 80)));
        assertEquals("http://[2001:db8:0:1:1:1:1:1]:80/",
                ChappeListener.httpUrl(new InetSocketAddress(InetAddress.getByName("2001:db8:0:1:1:1:1:1"), 80)),
                "a single zero group is not compressed (RFC 5952)");
        assertEquals("http://[fe80::]:80/",
                ChappeListener.httpUrl(new InetSocketAddress(InetAddress.getByName("fe80::"), 80)));
    }

    @Test
    void ipv6ZoneIsPercentEncoded() throws Exception {
        InetAddress scoped = Inet6Address.getByAddress(null,
                InetAddress.getByName("fe80::1").getAddress(), 1);
        assertEquals("http://[fe80::1%251]:80/", ChappeListener.httpUrl(new InetSocketAddress(scoped, 80)));
    }

    @Test
    void unresolvedHostKeepsItsName() {
        assertEquals("http://example.invalid:80/",
                ChappeListener.httpUrl(InetSocketAddress.createUnresolved("example.invalid", 80)));
    }

    @Test
    void nullAddressRejected() {
        assertThrows(NullPointerException.class, () -> ChappeListener.httpUrl(null));
    }
}
