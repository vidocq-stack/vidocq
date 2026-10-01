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
package io.vidocq.runtime.spi.devconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A language a panel offers the page's code editor: checked where it is written, never on the page. */
class PanelLanguageTest {

    private static final String JDQL = "{\"mode\":\"query\",\"dialect\":{\"quote\":\"'\"},\"targets\":{\"Task\":"
            + "{\"detail\":\"table task\",\"attributes\":{\"title\":{\"type\":\"string\"}}}}}";

    @Test
    void aLanguageIsAnIdAndTheTextOfAJsonObjectKeptAsItIs() {
        PanelLanguage jdql = new PanelLanguage("jdql", JDQL);

        assertEquals("jdql", jdql.id());
        assertEquals(JDQL, jdql.json(), "served as it is written");
    }

    @Test
    void anIdOutsideTheKeyRuleIsRefused() {
        for (String id : new String[] {"", "JDQL", "1jdql", "jd ql", "j".repeat(41)}) {
            assertThrows(IllegalArgumentException.class, () -> new PanelLanguage(id, JDQL), id);
        }
        assertThrows(NullPointerException.class, () -> new PanelLanguage(null, JDQL));
    }

    @Test
    void aTextThatIsNoJsonObjectIsRefusedNamingTheId() {
        for (String json : new String[] {"", "[]", "\"jdql\"", "{\"mode\":", "{\"a\":1,\"a\":2}", "{} {}"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new PanelLanguage("jdql", json), json);
            assertTrue(refused.getMessage().startsWith("language 'jdql' is not a JSON object"), refused.getMessage());
        }
        assertThrows(NullPointerException.class, () -> new PanelLanguage("jdql", null));
    }

    @Test
    void aTextPastOneMebibyteOrPastTheDepthOfAJsonArgumentIsRefused() {
        String head = "{\"detail\":\"";
        String tail = "\"}";
        String largest = head + "a".repeat(PanelLanguage.MAX_JSON - head.length() - tail.length()) + tail;
        assertEquals(1024 * 1024, largest.length());
        assertEquals(largest, new PanelLanguage("jdql", largest).json());
        assertThrows(IllegalArgumentException.class, () -> new PanelLanguage("jdql", largest.replace("{", "{ ")));

        int depth = PanelAction.Argument.MAX_JSON_DEPTH;
        String deepest = "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
        assertEquals(deepest, new PanelLanguage("jdql", deepest).json());
        assertThrows(IllegalArgumentException.class,
                () -> new PanelLanguage("jdql", "{\"a\":" + deepest + "}"), "one level more");
    }
}
