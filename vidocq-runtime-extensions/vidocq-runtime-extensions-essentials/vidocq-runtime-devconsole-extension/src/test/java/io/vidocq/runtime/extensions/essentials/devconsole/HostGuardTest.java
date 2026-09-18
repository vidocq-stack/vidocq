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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code Host} a request may carry: what the browser writes for the console's own address, never a name some
 * other site resolved to it (DNS rebinding).
 */
class HostGuardTest {

    private static final int BOUND = 8888;

    @ParameterizedTest(name = "{0} on {1}: {2}")
    @CsvSource(nullValues = "(none)", value = {
            // the loopback names, with or without the bound port
            "127.0.0.1,              127.0.0.1, true",
            "127.0.0.1:8888,         127.0.0.1, true",
            "localhost:8888,         127.0.0.1, true",
            "LocalHost:8888,         127.0.0.1, true",
            "localhost,              127.0.0.1, true",
            "[::1]:8888,             127.0.0.1, true",
            "[::1],                  127.0.0.1, true",
            // the configured host, whatever it is
            "127.0.1.1:8888,         127.0.1.1, true",
            "dev.example.com:8888,   dev.example.com, true",
            "DEV.example.com:8888,   dev.example.com, true",
            "[fe80::1]:8888,         fe80::1, true",
            // a console open to the network is reached by address: an address is never a rebinding
            "192.168.1.20:8888,      0.0.0.0, true",
            "[fe80::1]:8888,         ::, true",
            // but only by address: a name that resolved to it is the attack
            "evil.example.com:8888,  0.0.0.0, false",
            "evil.example.com:8888,  127.0.0.1, false",
            "localhost.evil.com:8888, 127.0.0.1, false",
            "127.0.0.1.nip.io:8888,  127.0.0.1, false",
            // on loopback, the network's addresses are not the console's
            "192.168.1.20:8888,      127.0.0.1, false",
            // another port, or a port that is no port
            "127.0.0.1:9999,         127.0.0.1, false",
            "localhost:,             127.0.0.1, false",
            "localhost:8888x,        127.0.0.1, false",
            "localhost:+8888,        127.0.0.1, false",
            "[::1]8888,              127.0.0.1, false",
            "::1,                    127.0.0.1, false",
            // nothing, or nothing a host is
            "(none),                 127.0.0.1, false",
            "'',                     127.0.0.1, false",
            "'  ',                   127.0.0.1, false",
            "'localhost :8888',      127.0.0.1, false",
            "user@localhost:8888,    127.0.0.1, false"})
    void onlyTheConsolesOwnAddressGetsIn(String host, String configuredHost, boolean allowed) {
        assertEquals(allowed, new HostGuard(configuredHost).allows(host, BOUND));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"localhost:8888", "127.0.0.1:8888"})
    void noPortIsTheConsolesUntilItIsBound(String host) {
        assertEquals(false, new HostGuard("127.0.0.1").allows(host, 0));
    }
}
