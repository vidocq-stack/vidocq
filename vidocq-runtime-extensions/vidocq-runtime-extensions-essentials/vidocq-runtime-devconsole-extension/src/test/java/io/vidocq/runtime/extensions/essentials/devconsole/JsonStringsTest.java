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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The reader of an action request's body: one object of strings, its escapes decoded, nothing else. */
class JsonStringsTest {

    @Test
    void readsTheMembersInDocumentOrder() {
        Map<String, String> members = JsonStrings.object(" { \"logger\" : \"com.acme\" , \"level\":\"DEBUG\" } ");

        assertEquals(List.of("logger", "level"), List.copyOf(members.keySet()));
        assertEquals("DEBUG", members.get("level"));
        assertEquals(Map.of(), JsonStrings.object("{}"));
    }

    @Test
    void decodesEveryEscape() {
        Map<String, String> members = JsonStrings.object("{\"a\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\u00C9\"}");

        assertEquals("\"\\/\b\f\n\r\t\u00e9\u00c9", members.get("a"));
    }

    @Test
    void refusesAnythingButOneObjectOfStrings() {
        for (String text : List.of("", "[]", "{\"a\":1}", "{\"a\":\"x\",\"a\":\"y\"}", "{\"a\":\"x\"} x",
                "{\"a\":\"x\";\"b\":\"y\"}", "{\"a\":\"\u0001\"}", "{\"a\":\"x", "{a:\"x\"}")) {
            assertThrows(IllegalArgumentException.class, () -> JsonStrings.object(text), text);
        }
    }

    @Test
    void refusesAnEscapeItDoesNotKnow() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> JsonStrings.object("{\"a\":\"\\x\"}"));

        assertEquals("an unknown escape \\x", refused.getMessage());
    }

    @Test
    void refusesAUnicodeEscapeThatIsNotFourHexadecimalDigits() {
        IllegalArgumentException notHexadecimal = assertThrows(IllegalArgumentException.class,
                () -> JsonStrings.object("{\"a\":\"\\u00zz\"}"));
        IllegalArgumentException cut = assertThrows(IllegalArgumentException.class,
                () -> JsonStrings.object("{\"a\":\"\\u00"));

        assertEquals("a \\u escape that is not hexadecimal", notHexadecimal.getMessage());
        assertEquals("end of text", cut.getMessage());
    }
}
