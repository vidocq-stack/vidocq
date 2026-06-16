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
package io.vidocq.runtime.cli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesTextTest {

    @Test
    void getExistingKey() {
        String text = "# comment\nvidocq.http.port=8080\n";
        assertEquals("8080", PropertiesText.get(text, "vidocq.http.port").orElseThrow());
    }

    @Test
    void getMissingKey() {
        assertTrue(PropertiesText.get("a=1\n", "b").isEmpty());
    }

    @Test
    void getIgnoresCommentsAndBlanks() {
        String text = "# port=1\n\n  ! bang=2\nport=3\n";
        assertEquals("3", PropertiesText.get(text, "port").orElseThrow());
    }

    @Test
    void colonSeparatorSupported() {
        assertEquals("v", PropertiesText.get("k:v\n", "k").orElseThrow());
    }

    @Test
    void valueWhitespaceTrimmedOnRead() {
        assertEquals("8080", PropertiesText.get("port =  8080\n", "port").orElseThrow());
    }

    @Test
    void setUpdatesInPlacePreservingCommentsAndOrder() {
        String text = "# header\nport=8080\nname=demo\n";
        String out = PropertiesText.set(text, "port", "9090");
        assertEquals("# header\nport=9090\nname=demo\n", out);
    }

    @Test
    void setPreservesOriginalSeparatorSpacing() {
        String out = PropertiesText.set("port = 8080\n", "port", "9090");
        assertEquals("port = 9090\n", out);
    }

    @Test
    void setAppendsNewKey() {
        String out = PropertiesText.set("port=8080\n", "host", "localhost");
        assertEquals("port=8080\nhost=localhost\n", out);
    }

    @Test
    void setAppendsWhenNoTrailingNewline() {
        String out = PropertiesText.set("port=8080", "host", "localhost");
        assertEquals("port=8080\nhost=localhost\n", out);
    }

    @Test
    void setOnEmptyText() {
        assertEquals("k=v\n", PropertiesText.set("", "k", "v"));
    }

    @Test
    void entriesAreOrdered() {
        Map<String, String> map = PropertiesText.entries("b=2\na=1\nc=3\n");
        assertEquals("[b, a, c]", map.keySet().toString());
        assertEquals("1", map.get("a"));
    }

    @Test
    void onlyFirstAssignmentIsUpdated() {
        String out = PropertiesText.set("k=1\nk=2\n", "k", "9");
        assertEquals("k=9\nk=2\n", out);
    }
}
