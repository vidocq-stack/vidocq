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
package io.vidocq.runtime.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserExtensionTest {

    @Test
    void bareExtensionListsInstalled() {
        Command c = CliParser.parse(new String[]{"extension"});
        var listing = assertInstanceOf(Command.Extension.Listing.class, c);
        assertTrue(listing.installed());
        assertTrue(!listing.available());
    }

    @Test
    void listAvailableFlipsToAvailableOnly() {
        Command c = CliParser.parse(new String[]{"extension", "list", "--available"});
        var listing = assertInstanceOf(Command.Extension.Listing.class, c);
        assertTrue(listing.available());
        assertTrue(!listing.installed());
    }

    @Test
    void listAllShowsBoth() {
        var listing = assertInstanceOf(Command.Extension.Listing.class,
                CliParser.parse(new String[]{"ext", "ls", "--all"}));
        assertTrue(listing.installed());
        assertTrue(listing.available());
    }

    @Test
    void listRefreshForcesANewResolution() {
        var listing = assertInstanceOf(Command.Extension.Listing.class,
                CliParser.parse(new String[]{"extension", "list", "--refresh"}));
        assertTrue(listing.installed());
        assertTrue(listing.refresh());
        assertTrue(!Command.Extension.Listing.defaults().refresh());
    }

    @Test
    void addCollectsIds() {
        Command c = CliParser.parse(new String[]{"extension", "add", "cassini-rest", "knock-health"});
        var add = assertInstanceOf(Command.Extension.Add.class, c);
        assertEquals(List.of("cassini-rest", "knock-health"), add.ids());
    }

    @Test
    void removeCollectsIds() {
        Command c = CliParser.parse(new String[]{"ext", "rm", "knock-health"});
        var remove = assertInstanceOf(Command.Extension.Remove.class, c);
        assertEquals(List.of("knock-health"), remove.ids());
    }

    @Test
    void addWithoutIdsFails() {
        assertThrows(CliException.class, () -> CliParser.parse(new String[]{"extension", "add"}));
    }

    @Test
    void unknownSubCommandFails() {
        assertThrows(CliException.class, () -> CliParser.parse(new String[]{"extension", "frobnicate"}));
    }
}
