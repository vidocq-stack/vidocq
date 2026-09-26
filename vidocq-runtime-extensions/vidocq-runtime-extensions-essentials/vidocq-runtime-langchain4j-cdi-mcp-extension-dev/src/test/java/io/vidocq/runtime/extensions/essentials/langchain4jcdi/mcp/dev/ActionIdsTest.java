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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Action ids follow the console's key rule, are cut to fit, and never collide. */
class ActionIdsTest {

    @Test
    void aNameIsLowercasedAndEveryOtherCharacterBecomesAHyphen() {
        ActionIds ids = new ActionIds();

        assertEquals("tool.current-time", ids.named("tool.", "current_time"));
        assertEquals("prompt.m-t-o", ids.named("prompt.", "Météo"));
        assertEquals("tool.a.b-c", ids.named("tool.", "a.b c"));
    }

    @Test
    void aLongNameIsCutToFortyCharacters() {
        String id = new ActionIds().named("tool.", "a_tool_whose_name_is_far_longer_than_forty_characters");

        assertEquals("tool.a-tool-whose-name-is-far-longer-tha", id);
        assertEquals(40, id.length());
        PanelSample.requireKey(id);
    }

    @Test
    void aCollisionGetsASuffixThatStillFits() {
        ActionIds ids = new ActionIds();
        String name = "x".repeat(60);

        assertEquals("tool.current-time", ids.named("tool.", "current-time"));
        assertEquals("tool.current-time-2", ids.named("tool.", "current_time"));
        assertEquals("tool.current-time-3", ids.named("tool.", "Current Time"));
        String first = ids.named("tool.", name);
        String second = ids.named("tool.", name + "y");
        assertEquals(40, first.length());
        assertEquals(40, second.length());
        assertEquals(first.substring(0, 38) + "-2", second);
    }

    @Test
    void aUriIsTheFirstEightHexCharactersOfItsSha256() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("time://utc".getBytes(StandardCharsets.UTF_8))).substring(0, 8);

        assertEquals("res." + expected, new ActionIds().hashed("res.", "time://utc"));
        assertEquals("res.860cd4a5", new ActionIds().hashed("res.", "time://utc"));
        assertEquals("tpl.72bba68d", new ActionIds().hashed("tpl.", "time://zone/{zone}"));
    }
}
